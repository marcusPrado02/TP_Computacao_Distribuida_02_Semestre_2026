# Pacote `rpc`

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

Voltar ao [índice](README.md) · Tema: [`../rede-grpc.md`](../rede-grpc.md)

Toda a comunicação entre nós. **Saída** pelo `PeerNetwork` (cliente), **entrada** pelo `NodeServiceImpl` (servidor),
**autenticação** pelo `ClusterAuth`, **conversões** pelo `Protos`.

---

## PeerNetwork

Código: [`PeerNetwork.java`](../../src/main/java/br/pucminas/cd/aco/rpc/PeerNetwork.java)

### Responsabilidade
Cliente gRPC do nó. Mantém um canal e um stub para cada peer, carimba o relógio de Lamport em toda mensagem,
aplica *deadline* em toda chamada e mantém a **visão de quais peers estão vivos**.
Implementa `ElectionTransport` e `MutexTransport`, e oferece os envios das rodadas.

### Quem cria / quem usa
- Criado por `Node` com `(nodeId, clock, PEERS, CLUSTER_TOKEN)`.
- Usado por `BullyElection`, `LeaderMonitor`, `RicartAgrawala` (via interfaces), `RoundCoordinator`, `RoundWorker`
  e `NodeServiceImpl` (só `header()` e `allPeers()`).

### Estado
| Campo | Significado |
|---|---|
| `FAST_TIMEOUT_MS = 500` | Deadline das mensagens de controle |
| `BROADCAST_TIMEOUT_MS = 2_000` | Deadline do `UpdatePheromone` (matriz `n×n` é maior) |
| `channels`, `stubs` | `HashMap` por id; preenchidos no construtor e nunca mais alterados |
| `alive` | `ConcurrentHashMap.newKeySet()`; começa com **todos** os peers (otimista) |
| `executor` | Virtual threads para envios assíncronos e paralelos |

### Métodos
| Método | Bloqueia? | O que faz |
|---|---|---|
| `Header header()` | não | `sender_id` + `clock.tick()`. Cada chamada é um evento de envio. |
| `Set<Integer> allPeers()` | não | Todos os ids configurados. |
| `boolean sendElection(int to)` | até 500 ms | ELECTION; devolve `ok` da resposta, ou `false` se falhou. |
| `void broadcastCoordinator()` | não | COORDINATOR a todos, cada um numa virtual thread. |
| `boolean heartbeat(int to)` | até 500 ms | `true` se respondeu. |
| `Set<Integer> alivePeers()` | não | Cópia imutável de `alive`. |
| `boolean sendRequest(int to, long ts)` | até 500 ms | `RequestCS(request_ts = ts)`. |
| `void sendReply(int to, long requestTs)` | não | `ReplyCS(request_ts)` numa virtual thread. |
| `void broadcastStartRound(int round)` | não | `StartRound` a todos, assíncrono. |
| `void sendRoundDone(int leader, int round, Tour t)` | até 500 ms | `RoundDone` ao líder (chamado pela thread da rodada). |
| `void broadcastPheromone(int round, double[] tau, Tour best, int coordinator)` | até `BROADCAST_TIMEOUT_MS` (2 s no padrão) | Envia a matriz a todos **em paralelo** e espera todas as respostas. O coordenador é sempre tentado; peers em quarentena são pulados. Falhas são logadas, não lançadas. |
| `void close()` | não | Encerra o executor e todos os canais. |

### O núcleo: `call(peer, timeout, rpc, headerOf)`
1. Executa o RPC no stub com `withDeadlineAfter(timeout)`.
2. Sucesso: `clock.onReceive(resposta.header.lamport)`; se o peer não estava em `alive`, adiciona e loga `N<id> voltou a responder`.
3. `StatusRuntimeException` (timeout, conexão recusada, `UNAUTHENTICATED`...): remove de `alive`, loga `N<id> inacessível (<código>)` e devolve vazio.

`ack(...)` é o atalho para RPCs que devolvem `Ack`: só interessa se deu certo.

### Detalhes e decisões
- **A visão de vivos é atualizada "de carona"** em todo RPC, sem tráfego extra. Ela é usada pelo Ricart-Agrawala
  (quem pedir/aguardar) e pelo coordenador (quem esperar na rodada).
- **`header()` é chamado dentro do lambda**, ou seja, no momento do envio, não no momento em que o envio foi agendado.
- **Base do pedido de feromônio construída uma vez** (`base`); só o `Header` muda por destino, pois cada envio é um evento distinto.
- **Canais em texto puro (`usePlaintext`)**: sem TLS. Ver limitações de segurança.

---

## NodeServiceImpl

Código: [`NodeServiceImpl.java`](../../src/main/java/br/pucminas/cd/aco/rpc/NodeServiceImpl.java)

### Responsabilidade
Servidor gRPC: implementa os 8 RPCs de `NodeService`. Para cada mensagem recebida, valida o remetente,
aplica a regra de recebimento do Lamport e delega ao módulo dono. **Nenhum handler bloqueia.**

### Quem cria / quem usa
- Criado por `Node` e registrado no `Server` (atrás dos interceptors).
- Chamado pelas threads do servidor gRPC.

### Roteamento
| RPC | Delegação | Resposta |
|---|---|---|
| `Election` | `election.onElection(from)` | `ElectionReply(ok)` |
| `Coordinator` | `election.onCoordinator(leader_id)` | `Ack` |
| `Heartbeat` | — (só prova que está vivo) | `Ack` |
| `RequestCS` | `mutex.onRequest(request_ts, from)` | `Ack` (entrega, não permissão) |
| `ReplyCS` | `mutex.onReply(from, request_ts)` | `Ack` |
| `StartRound` | `worker.onStartRound(round)` | `Ack` |
| `RoundDone` | `coordinator.onRoundDone(round, from, tour)` | `Ack` |
| `UpdatePheromone` | `worker.onPheromoneUpdate(round, tau, best)` | `Ack` |

### `receive(Header)`
- Se `sender_id` não está em `PEERS`: lança `Status.PERMISSION_DENIED` ("remetente desconhecido").
  O `TransmitStatusRuntimeExceptionInterceptor` (registrado em `Node`) faz o cliente receber esse código em vez de `UNKNOWN`.
- Senão: `clock.onReceive(lamport)` e devolve o id.

Toda resposta usa `network.header()`, então a resposta também é um evento de envio com relógio próprio.

### Detalhes e decisões
- **Por que nunca bloquear?** Se o handler de `RequestCS` esperasse a seção crítica, dois nós esperando um pelo outro
  travariam as threads do servidor (deadlock distribuído). Por isso a permissão é um RPC separado (`ReplyCS`).

---

## ClusterAuth

Código: [`ClusterAuth.java`](../../src/main/java/br/pucminas/cd/aco/rpc/ClusterAuth.java)

### Responsabilidade
Autenticação por token compartilhado (`CLUSTER_TOKEN`), no header de metadados `x-cluster-token`.

### Métodos
| Método | Usado por | O que faz |
|---|---|---|
| `static ClientInterceptor clientInterceptor(String token)` | `PeerNetwork` (todo stub) | Anexa o header em toda chamada de saída. |
| `static ServerInterceptor serverInterceptor(String token)` | `Node` (servidor) | Rejeita a chamada com `UNAUTHENTICATED` se o header falta ou não confere. |

### Detalhes
- **`MessageDigest.isEqual`:** comparação em tempo constante, para não vazar o token por diferença de tempo de resposta.
- **Duas camadas:** o token prova que a chamada vem "de dentro do cluster" (`UNAUTHENTICATED` se não);
  `NodeServiceImpl.receive` confere se o `sender_id` é um peer conhecido (`PERMISSION_DENIED` se não).
- **Limitações:** sem TLS o token trafega em claro; quem tem o token pode forjar o `sender_id` de outro membro.
  Evolução natural: mTLS com um certificado por nó.

---

## Protos

Código: [`Protos.java`](../../src/main/java/br/pucminas/cd/aco/rpc/Protos.java)

Utilitário de pacote (não público) com conversões entre tipos do domínio e campos `repeated` do protobuf:
`boxed(int[])`, `boxed(double[])`, `unboxDoubles(List<Double>)` e `tour(List<Integer>, double)`.
`tour` devolve `null` se a lista vier vazia (mensagem sem rota), o que `BestTracker.offer` ignora.

---

## Código gerado pelo `protoc`

A partir de [`node.proto`](../../src/main/proto/node.proto), o `protobuf-maven-plugin` gera, no pacote
`br.pucminas.cd.aco.grpc`, uma classe por mensagem (`Header`, `Ack`, `CsRequest`, `CsReply`, `PheromoneUpdate`...)
e o `NodeServiceGrpc` (base do servidor `NodeServiceImplBase` e os stubs do cliente).
O código fica em `target/generated-sources/protobuf/` e **não deve ser editado nem versionado**;
o `build-helper-maven-plugin` registra essas pastas para as IDEs enxergarem as classes.
