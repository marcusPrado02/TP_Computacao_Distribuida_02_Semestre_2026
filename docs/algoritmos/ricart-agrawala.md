# Exclusão mútua distribuída: Ricart-Agrawala

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

Relacionados: [Arquitetura](../ARQUITETURA.md) · [Lamport](./lamport.md) · [Bully](./bully.md)

Código: [`mutex/RicartAgrawala.java`](../../src/main/java/br/pucminas/cd/aco/mutex/RicartAgrawala.java),
[`mutex/MutexTransport.java`](../../src/main/java/br/pucminas/cd/aco/mutex/MutexTransport.java),
[`round/RoundWorker.java`](../../src/main/java/br/pucminas/cd/aco/round/RoundWorker.java),
[`rpc/PeerNetwork.java`](../../src/main/java/br/pucminas/cd/aco/rpc/PeerNetwork.java)

---

## 1. Problema

Cada nó tem uma **réplica** da matriz de feromônio. Ao fim das suas formigas, o nó faz:

```
ler matriz → evaporar → depositar feromônio da melhor rota → enviar a matriz nova a todos
```

Se dois nós fazem isso ao mesmo tempo, cada um parte da mesma versão, e cada um sobrescreve a matriz
do outro ao receber o broadcast. Resultado: **lost update** (um depósito some) e **réplicas
divergentes** (cada nó termina com uma matriz diferente). O teste
`PheromoneMatrixTest.semExclusaoMutuaAsReplicasDivergemEPerdemDeposito` reproduz exatamente isso.

A solução é tornar "atualizar + propagar" uma **seção crítica (SC) distribuída**: no máximo um nó por
vez, em todo o cluster. Não há memória compartilhada nem servidor central de lock, então os nós
precisam combinar a ordem trocando mensagens.

## 2. Intuição e definição

**Analogia:** para usar a única sala de reunião, você pede permissão a todos os colegas. Cada um
responde "pode" na hora, a não ser que esteja na sala ou tenha pedido antes de você; nesse caso guarda
seu pedido e responde quando sair. Você entra quando todos disseram "pode". "Antes" é decidido pelo
número de Lamport do pedido, com o crachá (id) como desempate.

### Estados de cada nó

- `RELEASED`: não quer a SC.
- `WANTED`: pediu e está esperando permissões.
- `HELD`: está dentro da SC.

### Pseudocódigo (Ricart & Agrawala, 1981)

```
acquire():
    estado := WANTED
    ts := relógio.tick()
    envia REQUEST(ts, meu_id) a todos os outros N−1 nós
    espera N−1 REPLYs
    estado := HELD

ao receber REQUEST(t, j):
    se estado = HELD
       ou (estado = WANTED e (ts, meu_id) < (t, j)):
        adia: guarda j na fila
    senão:
        envia REPLY a j

release():
    estado := RELEASED
    envia REPLY a todos da fila; esvazia a fila
```

`(a, i) < (b, j)` significa `a < b`, ou `a = b` e `i < j` (ordem lexicográfica).

## 3. Como está implementado

### Mapeamento regra → código

| Regra | Onde |
|---|---|
| Pedir a SC | `RicartAgrawala.acquire()`: `state = WANTED`, `ts = clock.tick()`, `targets = transport.alivePeers()`, `awaiting = targets` |
| Enviar REQUEST a todos | um `executor.submit` por peer chamando `transport.sendRequest(peer, ts)` (threads virtuais, em paralelo) |
| Esperar todos os REPLYs | laço `while (!awaiting.isEmpty()) wait(LIVENESS_POLL_MS)` |
| Decidir responder ou adiar | `onRequest(ts, from)` → `shouldDefer(reqTs, reqId)` |
| Regra de adiamento | `shouldDefer`: `HELD -> true`; `WANTED -> requestTs < reqTs \|\| (requestTs == reqTs && selfId < reqId)`; `RELEASED -> false` |
| Guardar adiados | `deferred: Map<Integer, Long>` (id do solicitante → `request_ts` dele) |
| Receber REPLY | `onReply(from, ts)`: ignora se `state != WANTED` ou `ts != requestTs`; senão remove `from` de `awaiting` e `notifyAll()` |
| Sair da SC | `release()`: exige `HELD`, vai para `RELEASED` e chama `replyDeferred()` |

O uso fica em `RoundWorker.runRound(...)`:

```java
mutex.acquire();
try {
    pheromone.evaporateAndDeposit(local, cfg.rho());
    best.offer(local);
    network.broadcastPheromone(round, pheromone.flatten(), best.get(), coordinator); // síncrono
} finally {
    mutex.release();
}
```

### Por que o broadcast é síncrono dentro da SC

`broadcastPheromone` só retorna depois que todos os peers confirmaram o `UpdatePheromone` (ou o prazo
de 2 s estourou). Só então o nó libera e envia os REPLYs adiados. Assim, o próximo nó a entrar na SC
**já tem a matriz atualizada** antes de receber a última permissão. É isso que faz a exclusão mútua
resolver o lost update: a SC serializa as versões da matriz.

### Concorrência interna

- Todo o estado (`state`, `requestTs`, `awaiting`, `deferred`) é protegido pelo monitor do objeto
  (`synchronized` / `wait` / `notifyAll`).
- Os handlers gRPC (`onRequest`, `onReply`) **nunca bloqueiam**: decidem sob o lock e saem. O envio
  de REPLY é assíncrono (`PeerNetwork.sendReply` submete a um executor).
- Por isso `ReplyCS` é um RPC separado do `RequestCS`: se a permissão fosse a resposta do próprio
  `RequestCS`, o handler teria que ficar bloqueado enquanto adia, segurando uma thread do servidor.
- `acquire()` reentrante e `release()` fora da SC lançam `IllegalStateException` (erro de programação).
- Se a thread é interrompida durante a espera, o pedido é abandonado: volta para `RELEASED` e responde
  os adiados, para não deixar ninguém esperando por um pedido que não existe mais.

## 4. Mensagens gRPC

| RPC | Requisição | Resposta | Observação |
|---|---|---|---|
| `RequestCS` | `CsRequest { header, request_ts }` | `Ack` | `request_ts` fixo durante a espera; `header.lamport` muda a cada envio |
| `ReplyCS` | `CsReply { header, request_ts }` | `Ack` | `request_ts` diz **qual** pedido está sendo autorizado |
| `UpdatePheromone` | `PheromoneUpdate { header, round, tau[n·n], best_tour, best_length }` | `Ack` | enviado dentro da SC, prazo 2 s |

### Cenário: N1 e N2 pedem com o mesmo timestamp

N1 e N2 pedem ao mesmo tempo, ambos com `request_ts = 5`. N3 não quer a SC. Desempate pelo id: N1 vence.

```mermaid
sequenceDiagram
    participant N1
    participant N2
    participant N3
    Note over N1: WANTED, ts=5
    Note over N2: WANTED, ts=5
    N1->>N2: RequestCS(ts=5)
    N1->>N3: RequestCS(ts=5)
    N2->>N1: RequestCS(ts=5)
    N2->>N3: RequestCS(ts=5)
    Note over N2: recebe (5,N1); meu (5,N2) não é menor → responde
    N2-->>N1: ReplyCS(request_ts=5)
    Note over N1: recebe (5,N2); meu (5,N1) é menor → adia N2
    N3-->>N1: ReplyCS(request_ts=5)
    N3-->>N2: ReplyCS(request_ts=5)
    Note over N1: 2/2 REPLYs → HELD
    N1->>N2: UpdatePheromone(matriz)
    N1->>N3: UpdatePheromone(matriz)
    Note over N1: release() → RELEASED
    N1-->>N2: ReplyCS(request_ts=5) (o adiado)
    Note over N2: 2/2 REPLYs → HELD, já com a matriz de N1
```

## 5. Propriedades

| Propriedade | Situação |
|---|---|
| **Segurança** (no máximo 1 na SC) | Garantida com canais confiáveis e sem falhas. Para dois pedidos concorrentes, exatamente um deles adia, porque `(ts, id)` é ordem total. |
| **Sem deadlock** | Ordem total: não existe ciclo de "cada um espera o outro". |
| **Sem inanição / justiça** | Pedidos são atendidos em ordem de `(ts, id)`. Um pedido novo recebe ts maior (Lamport) que os já vistos, então não "fura a fila" indefinidamente. |
| **Mensagens por entrada** | 2(N−1): N−1 REQUEST + N−1 REPLY. No gRPC cada uma tem um `Ack`, então são 2(N−1) RPCs. |
| **Atraso de sincronização** | 1 mensagem: quem sai envia REPLY direto ao próximo. |
| Premissas | todos conhecem todos; canais confiáveis; relógio de Lamport atualizado em todo envio/recebimento; ids únicos. |

Comparação: um servidor central de lock usa 3 mensagens por entrada, mas é ponto único de falha e de
gargalo. Ricart-Agrawala melhora o algoritmo de Lamport (3(N−1) mensagens) juntando "release" e "reply".

O [`RicartAgrawalaSafetyTest`](../../src/test/java/br/pucminas/cd/aco/mutex/RicartAgrawalaSafetyTest.java)
verifica segurança e ausência de deadlock na prática: 4 nós, 30 entradas cada, rede simulada com atraso
aleatório. Se a regra de `shouldDefer` for quebrada (por exemplo, `WANTED -> false`), o teste detecta
4 nós dentro da SC ao mesmo tempo.

## 6. Decisões deste projeto

### `CsReply` carrega `request_ts`

Cenário que motivou a mudança:

1. N1 pede com `ts=10`. O `RequestCS` para N2 estoura o prazo de 500 ms (N2 estava lento), então N1
   conta como permissão (ver abaixo) e entra na SC.
2. N2 na verdade recebeu o pedido e o adiou. Mais tarde, envia o REPLY.
3. Enquanto isso N1 saiu e pediu de novo com `ts=50`.
4. O REPLY antigo chega. Sem identificação, N1 contaria como permissão para `ts=50`, e poderia entrar
   na SC enquanto N2 ainda está nela.

Com `request_ts` no `CsReply`, `onReply` descarta qualquer REPLY cujo `ts` não seja o pedido atual.
É a mesma ideia de número de sequência: a resposta precisa dizer a qual pergunta pertence. Por isso
`deferred` guarda o `ts` de cada solicitante, e não só o id.

### Peer inacessível conta como permissão

Se `sendRequest` falha (timeout ou conexão recusada), o peer é tratado como morto: morto não disputa a
SC. A alternativa seria esperar para sempre por quem caiu.

### Reavaliar vivos a cada 500 ms durante a espera

`awaiting.retainAll(transport.alivePeers())` a cada `LIVENESS_POLL_MS = 500`. Motivo: se o líder cair
**depois** de receber nosso REQUEST e adiá-lo, o REPLY nunca chega. O `LeaderMonitor` marca o líder como
inacessível em `PeerNetwork`, e na próxima verificação ele sai de `awaiting`. Sem isso, a demonstração de
failover travava todos os seguidores.

### Seção crítica com granularidade de rodada

Cada nó entra na SC uma vez por rodada, depois de rodar as formigas fora dela. A parte cara (construção
das rotas) é paralela; só a atualização curta da matriz é serializada.

## 7. Limitações e evolução

- **Detector impreciso pode violar a exclusão.** As duas decisões acima tratam "inacessível" como
  "morto". Se o peer só estava lento, ele pode estar na SC junto com quem considerou sua permissão
  concedida. O detector é por timeout, então não distingue os dois casos ([ARQUITETURA §6](../ARQUITETURA.md)).
- **Queda de seguidor não é vista de imediato.** Só o líder recebe heartbeat. Se um seguidor que adiou
  nosso pedido cai, ele só sai de `alivePeers()` quando alguém tentar falar com ele (por exemplo, o
  próximo `StartRound`). No pior caso, a espera dura até o timeout de 30 s da rodada.
- **Broadcast com falha parcial.** Se o `UpdatePheromone` para um peer falhar, aquele peer fica com a
  réplica desatualizada; não há versão para detectar isso.
- **Nó que volta** recomeça com a matriz inicial.

Para a Entrega 2: versão/época na matriz (controle de réplicas), 2PC no `UpdatePheromone`, ou trocar
por um algoritmo tolerante a falhas (lock com *lease* em um coordenador eleito, ou Maekawa com quóruns).

## 8. Como testar e observar

- [`RicartAgrawalaTest`](../../src/test/java/br/pucminas/cd/aco/mutex/RicartAgrawalaTest.java):
  `releasedRespondeNaHora`, `heldAdiaEAoLiberarResponde`, `wantedComPedidoMaisAntigoRespondeNaHora`,
  `wantedComPedidoMaisNovoAdia`, `empateNoTimestampDesempataPeloId`,
  `replyDePedidoAntigoNaoLiberaPedidoAtual`, `peerQueCaiDuranteEsperaDeixaDeSerAguardado`,
  `releaseForaDaSecaoCriticaEhErro`.
- `RicartAgrawalaSafetyTest.nuncaHaDoisNosNaSecaoCritica`: propriedade de segurança com concorrência real.
- [`PheromoneMatrixTest`](../../src/test/java/br/pucminas/cd/aco/aco/PheromoneMatrixTest.java): por que
  a SC é necessária (lost update) e que ela faz as réplicas convergirem.
- `ClusterIntegrationTest.elegeMaiorIdExecutaTodasAsRodadasEReplicasConvergem`: ao final, as matrizes
  dos 3 nós são idênticas.
- Nos logs: `Rodada k: ENTROU na seção crítica` e `Rodada k: SAIU da seção crítica` (o `SAIU` é logado
  *antes* do `release()`). Juntando os logs de todos os nós e ordenando pelo **relógio de Lamport**, nunca
  deve haver dois `ENTROU` sem um `SAIU` entre eles. `./tp check` faz exatamente isso; `./tp timeline cs`
  mostra a sequência.
- Por que ordenar por Lamport e não pela hora: se B entrou depois de A sair, há a cadeia causal
  `SAIU(A) → REPLY → ENTROU(B)`, então `L(SAIU_A) < L(ENTROU_B)` mesmo que os relógios físicos das máquinas
  discordem (LAN). A hora local só serve dentro de uma mesma máquina.

## 9. Perguntas prováveis na arguição

1. **Qual é o recurso protegido?** A matriz de feromônio global: atualizar e propagar.
2. **O que acontece sem exclusão mútua?** Lost update: réplicas divergem e depósitos somem.
3. **Quando um nó adia o REPLY?** Se está em HELD, ou em WANTED com pedido de prioridade maior
   (`(ts, id)` menor).
4. **Como resolvem empate de timestamp?** Pelo id do nó; o menor vence.
5. **Quantas mensagens por entrada?** 2(N−1).
6. **Por que o REPLY é um RPC separado?** Para o handler de `RequestCS` nunca bloquear enquanto adia.
7. **Por que o REPLY carrega `request_ts`?** Para que um REPLY atrasado de um pedido antigo não
   autorize o pedido atual.
8. **E se um nó cair?** Inacessível conta como permissão, e quem cai durante a espera deixa de ser
   aguardado. Isso mantém o sistema andando, mas pode violar a exclusão se o nó só estava lento.
