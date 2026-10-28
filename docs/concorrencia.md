# Concorrência dentro de um nó

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

Um nó não é um programa sequencial: ao mesmo tempo ele recebe RPCs, roda formigas, espera
permissões, manda heartbeats e às vezes coordena rodadas. Este documento mapeia **quais threads
existem**, **o que cada lock protege** e **por que o desenho evita deadlocks**, tanto locais quanto
distribuídos.

---

## 1. Mapa de threads

| Quem | Tipo | Criado em | O que executa |
|---|---|---|---|
| Servidor gRPC | pool padrão do gRPC/Netty | `Node` (`ServerBuilder.forPort`) | os handlers de `NodeServiceImpl` |
| `PeerNetwork.executor` | **virtual thread** por tarefa | `PeerNetwork` | envios assíncronos (`sendReply`, broadcasts) |
| `BullyElection.executor` | virtual thread por tarefa | `BullyElection` | `runElection` (uma eleição por vez) |
| `RicartAgrawala.executor` | virtual thread por tarefa | `RicartAgrawala` | um `sendRequest` por peer, em paralelo |
| `RoundWorker.executor` | **thread única** | `RoundWorker` | `runRound` (uma rodada por vez, em ordem) |
| `RoundCoordinator.executor` | thread única | `RoundCoordinator` | `loop` do líder |
| `LeaderMonitor.scheduler` | thread única agendada | `LeaderMonitor` | `check` a cada 1 s |
| `ForkJoinPool` comum | um worker por núcleo | JVM | formigas (`AntColony.runAnts` com `parallel()`) |
| `main` | plataforma | `NodeMain` | bloqueia em `awaitTermination` |
| `shutdown` | plataforma | `NodeMain` (shutdown hook) | `Node.close()` no Ctrl+C / `docker stop` |

### Por que virtual threads (Java 21)?

Envios gRPC aqui usam **stubs bloqueantes**. Bloquear uma virtual thread é barato: ela é
desmontada da thread do SO enquanto espera a rede. Assim dá para escrever código simples e
sequencial ("envia e espera o ack") e ainda ter centenas de envios simultâneos sem esgotar threads.

### Por que thread única em `RoundWorker` e `RoundCoordinator`?

- `RoundWorker`: garante **uma rodada por vez por nó**. Se um `StartRound(k+1)` chegar antes de a
  rodada k acabar, ele espera na fila. Também impede que o mesmo nó peça a seção crítica duas vezes
  ao mesmo tempo (o `RicartAgrawala.acquire` não é reentrante e lança `IllegalStateException` se tentar).
- `RoundCoordinator`: há no máximo um laço de rodadas; o `AtomicBoolean running` impede iniciar outro
  se o nó for eleito de novo com o laço ainda ativo.

---

## 2. O que cada lock protege

| Classe | Mecanismo | Estado protegido |
|---|---|---|
| `RicartAgrawala` | `synchronized (this)` + `wait`/`notifyAll` | `state`, `requestTs`, `awaiting`, `deferred` |
| `BullyElection` | `Object lock` + `wait`/`notifyAll` | `coordinatorSeen` |
| `BullyElection` | `AtomicInteger leaderId` (`getAndSet`) | líder atual; cada troca notifica os listeners uma vez |
| `BullyElection` | `AtomicBoolean electing` (`compareAndSet`) | no máximo uma eleição em andamento |
| `BullyElection` | `CopyOnWriteArrayList listeners` | lista de callbacks (raramente escrita) |
| `LamportClock` | métodos `synchronized` | `time` (`tick` e `onReceive` são atômicos) |
| `PheromoneMatrix` | métodos `synchronized` | a matriz τ (snapshot, atualização, flatten, replace) |
| `BestTracker` | métodos `synchronized` | melhor rota (`offer` é "compara e troca") |
| `RoundCoordinator` | `synchronized (this)` + `wait(POLL_MS)`/`notifyAll` | espera por `RoundDone` |
| `RoundCoordinator` | `ConcurrentHashMap` + `newKeySet()` | `done`: rodada → nós que terminaram |
| `RoundWorker` | `AtomicInteger lastRound` (`accumulateAndGet(max)`) | maior rodada vista |
| `PeerNetwork` | `ConcurrentHashMap.newKeySet()` | `alive`; `alivePeers()` devolve cópia imutável |
| `LeaderMonitor` | nenhum: `misses` só é tocado pela thread do scheduler | contagem de heartbeats perdidos |

### 2.1 Padrão wait/notify com condição em laço

Os três lugares que esperam (`RicartAgrawala.acquire`, `BullyElection.awaitCoordinator`,
`RoundCoordinator.awaitRound`) seguem o mesmo padrão:

```java
synchronized (lock) {
    while (!condicao) {
        lock.wait(timeout);   // libera o lock enquanto espera
        // reavalia; pode ter sido acordado por notifyAll, por timeout ou espuriamente
    }
}
```

- `while`, não `if`: *spurious wakeups* existem e outra thread pode ter mudado o estado de novo.
- **Timeout sempre presente**: `RicartAgrawala` reavalia os vivos a cada 500 ms; `BullyElection`
  desiste após 3 s; o coordenador reavalia a cada 500 ms e desiste após 30 s. Nenhuma espera é
  infinita só porque uma notificação se perdeu.

### 2.2 Formigas sem lock

As formigas leem `PheromoneMatrix.snapshot()`, uma **cópia** feita sob lock. Depois disso cada
formiga só lê a cópia e escreve nos próprios arrays. Não há contenção entre as threads do ForkJoin,
e uma atualização vinda da rede (`replace`) durante a construção não afeta a rodada em curso.

---

## 3. Handlers gRPC nunca bloqueiam

Regra de projeto: um handler de `NodeServiceImpl` só registra estado ou enfileira trabalho, e responde.

| Handler | Faz | Não faz |
|---|---|---|
| `election` | `onElection` → `startElection()` em background | não espera a eleição terminar |
| `requestCS` | `onRequest`: responde ou guarda em `deferred` | não espera sair da SC |
| `replyCS` | `onReply`: remove de `awaiting`, `notifyAll` | — |
| `startRound` | enfileira `runRound` | não roda as formigas |
| `roundDone` | marca em `done`, `notifyAll` | — |
| `updatePheromone` | `replace` da matriz (lock curto) | — |

**Por que isso importa: deadlock distribuído.** Suponha que `RequestCS` devolvesse a permissão na
própria resposta, e que o handler bloqueasse enquanto o nó não pudesse permitir. N1 está em HELD e
recebe o pedido de N2: o handler fica preso até N1 sair. Mas, para sair, N1 precisa terminar o
broadcast da matriz, cuja chamada para N2 depende de threads livres no servidor de N2, que podem estar
ocupadas por handlers presos do mesmo jeito. Com vários nós pedindo ao mesmo tempo, forma-se um ciclo
de espera entre máquinas, sem nenhum nó "errado" localmente. Separar a permissão em `ReplyCS`
(assíncrono) elimina esse ciclo: o handler responde o `Ack` na hora e a permissão vai depois.

Do mesmo modo, `RicartAgrawala.replyDeferred` é chamado **com o lock** de `RicartAgrawala`, o que só
é seguro porque `PeerNetwork.sendReply` apenas submete uma tarefa ao executor e retorna.

---

## 4. Locks e rede: nunca segurar lock esperando a rede

| Situação | Como o código evita |
|---|---|
| `acquire` enviando `REQUEST`s | os envios rodam no executor, **fora** do `synchronized`; o lock só é pego para alterar estado e para esperar (`wait` libera o lock) |
| `onRequest` decidindo responder | decide sob lock; envia (assíncrono) depois |
| `broadcastPheromone` dentro da SC | a SC distribuída é um estado (`HELD`), não um lock Java; os handlers locais continuam livres para receber `RequestCS` e adiar |
| `onRoundDone` | atualiza `done` (concorrente) e só pega o lock para `notifyAll` |

---

## 5. Desligamento (`Node.close`)

Ordem:

1. `monitor.close()`: para os heartbeats (não dispara eleição durante o desligamento).
2. `coordinator.close()`: interrompe o laço de rodadas.
3. `worker.close()`: interrompe a rodada em curso (`acquire` interrompido libera o pedido: volta a
   `RELEASED` e responde os adiados, para não travar os outros nós).
4. `election.close()` e `mutex.close()`: param os executores internos.
5. `server.shutdownNow()`: para de aceitar RPCs.
6. `network.close()`: fecha os executores de envio e os canais.
7. `server.awaitTermination(5 s)`.

Princípio: **primeiro parar quem gera trabalho, depois derrubar a infraestrutura que ele usa.**
`shutdownNow` interrompe as threads; por isso os laços de espera tratam `InterruptedException`.

O shutdown hook em `NodeMain` chama `close()` quando o processo recebe SIGTERM (`docker compose stop`) ou Ctrl+C.

---

## 6. Invariantes importantes

1. **No máximo uma rodada por nó em execução** (executor de thread única do `RoundWorker`).
2. **No máximo um pedido de SC por nó** (`acquire` lança exceção se não estiver em `RELEASED`).
3. **`release` só em `HELD`**; senão `IllegalStateException` (pega erros de uso cedo).
4. **REPLY só conta para o pedido atual** (`onReply` ignora `ts != requestTs` ou estado ≠ `WANTED`).
5. **Toda espera tem timeout ou reavaliação periódica.**
6. **Nenhum handler gRPC bloqueia**; nenhuma chamada de rede é feita segurando lock por tempo indeterminado.
7. **Uma eleição por vez** (`electing`), **um laço de coordenação por vez** (`running`).
8. **O relógio de Lamport é atualizado em todo envio (`header()` → `tick`) e todo recebimento**
   (servidor: `receive`; cliente: resposta em `PeerNetwork.call`).

Os testes que exercitam isso: `RicartAgrawalaSafetyTest` (4 nós, contenção real, nunca dois na SC),
`RicartAgrawalaTest` (estados e casos de borda) e `ClusterIntegrationTest` (tudo junto com gRPC).
Ver também [`./algoritmos/ricart-agrawala.md`](./algoritmos/ricart-agrawala.md) e
[`./algoritmos/bully.md`](./algoritmos/bully.md).

---

## 7. Perguntas prováveis na arguição

1. **Quantas threads um nó tem?** Pool do servidor gRPC, ForkJoin para as formigas, threads únicas
   para trabalhador, coordenador e monitor, e virtual threads para envios e eleição.
2. **Por que o handler de `RequestCS` não espera?** Para não prender threads do servidor e não
   criar ciclo de espera entre nós (deadlock distribuído); a permissão vai depois por `ReplyCS`.
3. **Por que `while` em volta do `wait`?** Wakeups espúrios e mudanças de estado entre o
   `notifyAll` e a retomada; a condição precisa ser reavaliada.
4. **As formigas disputam o lock da matriz?** Não: trabalham numa cópia (`snapshot`).
5. **O que acontece se `acquire` for interrompido?** Volta a `RELEASED` e responde os pedidos
   adiados, para não deixar os outros nós esperando.
6. **Por que virtual threads?** Código bloqueante simples sem custo de uma thread do SO por envio.
7. **Por que uma rodada por vez por nó?** Evita dois pedidos de SC simultâneos do mesmo nó e mantém as rodadas em ordem.
