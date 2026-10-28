# Rede: gRPC, Protocol Buffers e autenticação

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

Este documento explica como os nós conversam: o contrato (`src/main/proto/node.proto`), o lado
cliente (`rpc/PeerNetwork.java`), o lado servidor (`rpc/NodeServiceImpl.java`) e a autenticação
(`rpc/ClusterAuth.java`). É o requisito "Utilizar RPC" da Entrega 1.

---

## 1. Por que gRPC e Protocol Buffers

**RPC (Remote Procedure Call)** esconde a troca de mensagens atrás de uma chamada de método:
`stub.requestCS(msg)` parece local, mas serializa `msg`, envia pela rede, executa no outro nó e
devolve a resposta.

| Característica | Por que importa aqui |
|---|---|
| Contrato em `.proto` | um arquivo único define todas as mensagens; o código Java é gerado |
| Serialização binária (protobuf) | compacta; a matriz de feromônio (n² doubles) é a maior mensagem |
| HTTP/2 | uma conexão TCP por peer, multiplexando muitas chamadas simultâneas |
| Deadlines nativos | cada chamada tem prazo; é a base do nosso detector de falhas |
| Interceptors | ponto único para autenticação, sem tocar nos handlers |
| Status codes padronizados | `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `UNAUTHENTICATED`, `PERMISSION_DENIED`... |

O transporte é `grpc-netty-shaded` (Netty embutido, sem conflito de dependências).

---

## 2. O contrato: `NodeService`

Há **um único serviço**, exposto por todos os nós (nós simétricos: qualquer um pode virar líder).
Todos os RPCs são *unários* (uma requisição, uma resposta).

| RPC | Requisição → resposta | Campos principais | Quem chama quem | Módulo |
|---|---|---|---|---|
| `Election` | `ElectionMsg` → `ElectionReply` | `ok` na resposta | menor → maiores | Bully |
| `Coordinator` | `CoordinatorMsg` → `Ack` | `leader_id` | líder → todos | Bully |
| `Heartbeat` | `HeartbeatMsg` → `Ack` | — | seguidor → líder | detector de falha |
| `RequestCS` | `CsRequest` → `Ack` | `request_ts` | solicitante → todos | Ricart-Agrawala |
| `ReplyCS` | `CsReply` → `Ack` | `request_ts` do pedido autorizado | autorizador → solicitante | Ricart-Agrawala |
| `StartRound` | `RoundStart` → `Ack` | `round` | líder → todos | rodadas |
| `RoundDone` | `RoundResult` → `Ack` | `round`, `tour`, `length` | trabalhador → líder | rodadas |
| `UpdatePheromone` | `PheromoneUpdate` → `Ack` | `round`, `tau` (n·n, row-major), `best_tour`, `best_length` | dono da SC → todos | ACO |

### 2.1 `Header`: presente em toda mensagem

```proto
message Header {
  int32 sender_id = 1;   // quem enviou
  int64 lamport = 2;     // relógio de Lamport no momento do envio
}
```

Inclusive nas respostas (`Ack`, `ElectionReply`). Assim o relógio de Lamport é atualizado nos dois
sentidos de cada chamada. Detalhes em [`./algoritmos/lamport.md`](./algoritmos/lamport.md).

Note a diferença entre `header.lamport` (muda a cada envio) e `CsRequest.request_ts` (fixo durante
todo o pedido de seção crítica). O segundo é o que define a prioridade no Ricart-Agrawala.

### 2.2 Por que `ReplyCS` é um RPC separado

Seria natural o `RequestCS` devolver a permissão na própria resposta. Mas, se o nó que recebe o
pedido precisa **adiar** (está na SC), o handler teria de bloquear até ele sair, segurando uma
thread do servidor e a chamada do outro lado. Com vários nós fazendo isso ao mesmo tempo, o risco é
de deadlock distribuído. Por isso `RequestCS` só confirma recebimento (`Ack`) e a permissão vem
depois, quando houver, por um `ReplyCS` independente. Ver [`./concorrencia.md`](./concorrencia.md).

---

## 3. Lado cliente: `PeerNetwork`

`PeerNetwork` implementa `MutexTransport` e `ElectionTransport`: os algoritmos dependem dessas
interfaces, não do gRPC, o que permite testá-los com transportes falsos.

### 3.1 Canais e stubs

No construtor, para cada entrada de `PEERS`:
- `ManagedChannelBuilder.forTarget(address).usePlaintext().build()`: um canal HTTP/2 por peer,
  reutilizado em todas as chamadas (a conexão é aberta preguiçosamente e reconectada sozinha).
- Um **stub bloqueante** (`NodeServiceBlockingStub`) com o interceptor de token anexado.
- O peer começa no conjunto `alive` (otimista).

### 3.2 Deadlines

Toda chamada passa por `PeerNetwork.call`, que aplica `withDeadlineAfter`:

| Constante | Valor | Usada em |
|---|---|---|
| `FAST_TIMEOUT_MS` | 500 ms | eleição, coordinator, heartbeat, request/reply CS, start round, round done |
| `BROADCAST_TIMEOUT_MS` | 2 000 ms | `UpdatePheromone` (mensagem grande) |

### 3.3 Visão de quem está vivo

`call` é o único lugar que atualiza o conjunto `alive`:
- resposta recebida → aplica o Lamport da resposta (`clock.onReceive`) e marca o peer vivo
  (log "N<id> voltou a responder" se ele estava fora);
- `StatusRuntimeException` (timeout, conexão recusada, etc.) → remove de `alive` (log "N<id> inacessível").

Ou seja, a visão de vivos é **atualizada a cada chamada**, como efeito colateral. Não há um
detector global; o `LeaderMonitor` só garante que o líder é consultado a cada segundo.

### 3.4 Envios síncronos e assíncronos

| Método | Estilo | Por quê |
|---|---|---|
| `sendElection`, `heartbeat`, `sendRequest`, `sendRoundDone` | síncrono, devolve resultado | quem chama precisa saber se deu certo |
| `broadcastCoordinator`, `broadcastStartRound`, `sendReply` | assíncrono (virtual thread por envio) | quem chama não deve esperar; `sendReply` é chamado até com lock |
| `broadcastPheromone` | paralelo, mas **espera todos** os `Future` | precisa ser síncrono dentro da SC (ver [`./rodadas.md`](./rodadas.md) §7) |

O executor é `Executors.newVirtualThreadPerTaskExecutor()` (Java 21): uma thread virtual por envio
é barata, e o stub bloqueante dentro dela não ocupa uma thread do sistema operacional enquanto espera.

`header()` gera o cabeçalho com `clock.tick()`: **enviar é um evento** de Lamport.

---

## 4. Lado servidor: `NodeServiceImpl`

Cada handler segue o mesmo padrão:

```java
int from = receive(req.getHeader());   // valida remetente + Lamport
modulo.onAlgumaCoisa(...);             // delega, sem bloquear
ack(obs);                              // responde com Header atual
```

`receive(header)`:
1. Se `sender_id` não está em `PEERS`, lança `Status.PERMISSION_DENIED` ("remetente desconhecido").
2. Aplica a regra de recebimento de Lamport: `clock.onReceive(header.lamport)`.

**Nenhum handler bloqueia.** Eles só registram estado e/ou enfileiram trabalho:
`onStartRound` enfileira no executor do trabalhador, `onElection` dispara eleição em background,
`onRequest` responde ou adia, etc. Isso mantém as threads do servidor gRPC livres.

### 4.1 Interceptors do servidor

Montados em `Node` com `ServerInterceptors.intercept(service, A, B)`. O último da lista executa
primeiro, então a ordem efetiva é:

1. `ClusterAuth.serverInterceptor`: verifica o token **antes** de qualquer handler.
2. `TransmitStatusRuntimeExceptionInterceptor`: quando um handler lança `StatusRuntimeException`
   (como o `PERMISSION_DENIED` de `receive`), repassa esse status ao cliente. Sem ele, o cliente
   receberia um genérico `UNKNOWN`.

---

## 5. Autenticação: `ClusterAuth`

Problema: em uma LAN qualquer máquina pode abrir conexão na porta 50051 e, por exemplo, mandar um
`Coordinator` falso. Solução simples: **segredo compartilhado** do cluster.

- `CLUSTER_TOKEN` é obrigatório (o nó não sobe sem ele; ver `NodeConfig`).
- **Cliente:** `clientInterceptor(token)` anexa o header `x-cluster-token` em toda chamada
  (`MetadataUtils.newAttachHeadersInterceptor`).
- **Servidor:** `serverInterceptor(token)` lê o header. Se faltar ou for diferente, fecha a chamada
  com `UNAUTHENTICATED` ("token do cluster inválido") e devolve um listener vazio (o handler nunca roda).
- Comparação com `MessageDigest.isEqual`, que leva **tempo constante**. Um `equals` comum para no
  primeiro byte diferente; medindo o tempo de resposta, um atacante poderia descobrir o token byte a byte.

Duas camadas de verificação, portanto:

| Verificação | Onde | Erro |
|---|---|---|
| token correto | `ClusterAuth` (interceptor) | `UNAUTHENTICATED` |
| `sender_id` conhecido | `NodeServiceImpl.receive` | `PERMISSION_DENIED` |

Ambas são testadas em `ClusterIntegrationTest.chamadaSemTokenOuDeRemetenteDesconhecidoEhRecusada`.

### 5.1 Limitações de segurança

- **Sem TLS** (`usePlaintext`): o token trafega em claro; quem escuta a rede pode copiá-lo.
- **`sender_id` forjável:** quem tem o token pode se passar por outro membro, já que o id vem no corpo da mensagem.
- **Evolução natural: mTLS**, com um certificado por nó; a identidade passa a vir do certificado, não de um campo.

---

## 6. `Protos`: conversões

Campos `repeated` do protobuf viram `List<Integer>`/`List<Double>` em Java. `Protos` (package-private)
converte entre isso e os tipos do domínio:
- `boxed(int[])`, `boxed(double[])` para enviar;
- `unboxDoubles(List<Double>)` para receber a matriz;
- `tour(cities, length)` devolve `null` se a lista vier vazia (mensagem sem rota).

A matriz vai **achatada** em row-major: o elemento (i, j) fica na posição `i·n + j`
(`PheromoneMatrix.flatten` / `replace`, que valida o tamanho n²).

---

## 7. Como o código Java é gerado

No `pom.xml`:
1. `os-maven-plugin` detecta o sistema operacional (para baixar o `protoc` certo).
2. `protobuf-maven-plugin` roda `protoc` (protobuf 3.25.5) e o plugin `protoc-gen-grpc-java` (gRPC 1.68.1):
   - `compile` → mensagens em `target/generated-sources/protobuf/java`;
   - `compile-custom` → `NodeServiceGrpc` (stubs e base do servidor) em `target/generated-sources/protobuf/grpc-java`.
3. `build-helper-maven-plugin` registra essas duas pastas como fontes, para IDEs (VS Code/Eclipse) enxergarem as classes.
4. `javax.annotation-api` fornece o `@Generated` usado no código gerado.

Opções do `.proto`: `java_package = "br.pucminas.cd.aco.grpc"` e `java_multiple_files = true` (uma classe por mensagem).
**Nunca edite o código gerado**: altere o `.proto` e rode `mvn compile`.

---

## 8. Perguntas prováveis na arguição

1. **Por que gRPC e não sockets puros?** Contrato tipado, serialização eficiente, deadlines,
   interceptors e status codes prontos; o grupo foca nos algoritmos distribuídos.
2. **Como um nó sabe que outro caiu?** Uma chamada para ele falha (timeout de 500 ms ou conexão
   recusada) e ele sai do conjunto `alive`.
3. **Para que serve o `Header` em toda mensagem?** Identificar o remetente e carregar o relógio de Lamport, inclusive nas respostas.
4. **Por que `ReplyCS` não é a resposta do `RequestCS`?** Para o handler não bloquear quando a
   permissão precisa ser adiada; evita deadlock e threads presas.
5. **Como funciona a autenticação?** Token compartilhado no header `x-cluster-token`, verificado
   por interceptor em tempo constante; remetente fora de `PEERS` é recusado.
6. **Qual a fraqueza dela?** Sem TLS o token vai em claro, e o `sender_id` pode ser forjado por
   quem tem o token. Solução: mTLS.
7. **Qual a maior mensagem?** `UpdatePheromone`: n² doubles (60 cidades ≈ 3 600 valores ≈ 29 KB); por isso deadline de 2 s.
8. **Por que virtual threads?** Envios assíncronos baratos; uma thread por envio sem esgotar threads do SO.
