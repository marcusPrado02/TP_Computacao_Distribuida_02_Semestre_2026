# Pacote `election`

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

Voltar ao [índice](README.md) · Algoritmo: [`../algoritmos/bully.md`](../algoritmos/bully.md)

Três tipos: o algoritmo (`BullyElection`), o detector de falha do líder (`LeaderMonitor`)
e a interface de rede que os dois usam (`ElectionTransport`).

---

## ElectionTransport

Código: [`ElectionTransport.java`](../../src/main/java/br/pucminas/cd/aco/election/ElectionTransport.java)

### Responsabilidade
Define o mínimo que o Bully e o monitor precisam da rede. Isola o algoritmo do gRPC.

### Implementações
- `PeerNetwork` (produção, gRPC).
- `BullyElectionTest.FakeTransport` (testes, em memória).

### Métodos
| Método | Contrato |
|---|---|
| `Set<Integer> allPeers()` | Todos os peers configurados, vivos ou não. |
| `boolean sendElection(int to)` | Envia ELECTION; `true` se o peer respondeu OK (vivo e com id maior). Bloqueante. |
| `void broadcastCoordinator()` | Anuncia a todos que este nó é o novo líder. Assíncrono. |
| `boolean heartbeat(int to)` | `true` se o peer respondeu dentro do timeout. Bloqueante. |

---

## BullyElection

Código: [`BullyElection.java`](../../src/main/java/br/pucminas/cd/aco/election/BullyElection.java)

### Responsabilidade
Implementa o algoritmo Bully: o nó **vivo de maior id** vira coordenador.
Mantém o id do líder atual e avisa os interessados (listeners) quando ele muda.

### Quem cria / quem usa
- Criada por `Node`.
- `Node.start()` chama `startElection()` (eleição inicial).
- `LeaderMonitor` chama `startElection()` quando o líder para de responder.
- `NodeServiceImpl` chama `onElection(from)` e `onCoordinator(id)` ao receber os RPCs.
- `RoundCoordinator` consulta `isLeader()`; `RoundWorker` consulta `leaderId()` para saber a quem mandar `RoundDone`.
- `Node` registra um listener em `onLeaderChange` que dispara `RoundCoordinator.onBecameLeader()`.

### Estado
| Campo | Tipo | Significado |
|---|---|---|
| `COORDINATOR_TIMEOUT_MS` | `3_000` | Quanto esperar o COORDINATOR depois de receber um OK |
| `selfId` | `int` | Id deste nó |
| `leaderId` | `AtomicInteger` | Líder conhecido; `-1` até a primeira eleição |
| `electing` | `AtomicBoolean` | Há uma eleição rodando neste nó? Evita eleições duplicadas |
| `coordinatorSeen` | `boolean` (sob `lock`) | Chegou COORDINATOR durante a eleição atual? |
| `lock` | `Object` | Monitor para `coordinatorSeen` (`wait`/`notifyAll`) |
| `listeners` | `CopyOnWriteArrayList<IntConsumer>` | Avisados a cada troca de líder |
| `executor` | virtual threads | Onde a eleição roda (fora da thread do chamador) |

### Métodos públicos
| Método | Chamado de | O que faz |
|---|---|---|
| `void onLeaderChange(IntConsumer l)` | `Node` (montagem) | Registra listener. |
| `int leaderId()` | qualquer | Lê o líder atual. |
| `boolean isLeader()` | qualquer | `leaderId == selfId`. |
| `void startElection()` | `Node`, `LeaderMonitor`, handlers | Se `electing` estava `false`, marca `true` e submete `runElection` ao executor. Senão, ignora. |
| `boolean onElection(int from)` | thread gRPC | Se `from < selfId`: responde OK (`true`) e inicia a própria eleição. Senão `false`. |
| `void onCoordinator(int id)` | thread gRPC | Se `id < selfId`: **contesta** (nova eleição). Senão marca `coordinatorSeen`, acorda quem espera e chama `setLeader(id)`. |
| `void close()` | `Node.close` | Encerra o executor. |

### Fluxo de uma eleição (`runElection` → `electOrAwaitCoordinator`)
1. Zera `coordinatorSeen`.
2. Envia ELECTION, **um por vez, em ordem crescente**, a cada peer de id maior (`sendElection`, deadline de 500 ms cada).
3. Ninguém respondeu OK → `setLeader(selfId)` e `broadcastCoordinator()`.
4. Alguém respondeu OK → `awaitCoordinator()`: espera em `lock.wait(left)` até `coordinatorSeen` ou até 3 s.
5. Se o tempo acabou sem COORDINATOR, libera `electing` e **recomeça** (`startElection`).

### Thread-safety
- `leaderId` é `AtomicInteger`; `setLeader` usa `getAndSet`, então, com dois COORDINATOR concorrentes,
  só quem efetivamente muda o valor notifica os listeners (sem aviso duplicado).
- `electing` (`compareAndSet`) garante no máximo uma eleição em andamento por nó.
- `coordinatorSeen` só é lido/escrito dentro de `synchronized (lock)`.
- Os handlers (`onElection`, `onCoordinator`) nunca bloqueiam: o trabalho pesado vai para o executor.

### Detalhes e decisões
- **Contestação:** se um nó de id menor se declara líder (porque não conseguiu falar conosco), contestamos.
  É o "valentão" do nome: o maior sempre acaba assumindo.
- **Listeners são chamados na thread que trocou o líder** (thread gRPC ou da eleição). Por isso o listener
  em `Node` só dispara `onBecameLeader()`, que por sua vez submete o loop a outro executor e retorna logo.
- **Novo líder continua a rodada** a partir de `RoundWorker.lastRound() + 1` (ver [round.md](round.md#roundcoordinator)).

---

## LeaderMonitor

Código: [`LeaderMonitor.java`](../../src/main/java/br/pucminas/cd/aco/election/LeaderMonitor.java)

### Responsabilidade
Detector de falha simples: a cada 1 s envia heartbeat ao líder; **5 falhas seguidas** disparam uma nova eleição.

### Quem cria / quem usa
- Criado por `Node`; `Node.start()` chama `start()`; `Node.close()` chama `close()`.
- Usa `BullyElection.leaderId()` / `startElection()` e `ElectionTransport.heartbeat()`.

### Estado
| Campo | Significado |
|---|---|
| `MAX_MISSES = 5` | Falhas consecutivas toleradas |
| `misses` | Contador atual; só acessado pela thread do scheduler, por isso sem lock |
| `scheduler` | `ScheduledExecutorService` de uma thread |

### Métodos
| Método | O que faz |
|---|---|
| `void start()` | Agenda `check()` com atraso inicial de 1 s e período de 1 s (`scheduleAtFixedRate`). |
| `void close()` | `shutdownNow()` no scheduler. |
| `check()` (privado) | Se não há líder (`<= 0`), se o líder sou eu, ou se o heartbeat respondeu: zera `misses`. Senão incrementa; ao chegar a 3, loga, zera e chama `startElection()`. |

### Detalhes e decisões
- **`try/catch (RuntimeException)` em `check`:** `scheduleAtFixedRate` cancela silenciosamente a tarefa se uma exceção escapar.
  Sem o catch, um erro inesperado desligaria o monitor para sempre.
- **Tempo de detecção:** ~5 s (5 períodos de 1 s; o deadline de cada heartbeat é `max(1 s, RPC_TIMEOUT_MS)`) + a eleição em si.
- **Só o líder é monitorado.** A queda de um seguidor é percebida pelos outros apenas quando alguém tenta falar com ele
  (ver limitações em [`../ARQUITETURA.md`](../ARQUITETURA.md)).
- **Efeito colateral útil:** o heartbeat que falha remove o líder da visão de vivos do `PeerNetwork`, o que destrava
  quem estava esperando REPLY dele no Ricart-Agrawala (ver [mutex.md](mutex.md#ricartagrawala)).
