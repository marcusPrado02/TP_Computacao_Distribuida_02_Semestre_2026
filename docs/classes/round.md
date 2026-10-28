# Pacote `round`

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

Voltar ao [índice](README.md) · Tema: [`../rodadas.md`](../rodadas.md)

Une o ACO aos algoritmos distribuídos. Cada nó tem as duas classes, mas o `RoundCoordinator` só trabalha
enquanto o nó for líder.

---

## RoundSink

Código: [`RoundSink.java`](../../src/main/java/br/pucminas/cd/aco/round/RoundSink.java)

`@FunctionalInterface` com um método: `void onRoundDone(int round, int from, Tour tour)`.
É o "destino" do aviso de fim de rodada.

- Implementada por `RoundCoordinator`.
- Usada por `RoundWorker` (quando o próprio nó é o líder, avisa localmente sem passar pela rede)
  e por `NodeServiceImpl` (quando chega o RPC `RoundDone` de outro nó).
- Existe para quebrar a dependência circular: o trabalhador depende só desta interface, não do coordenador.

---

## RoundCoordinator

Código: [`RoundCoordinator.java`](../../src/main/java/br/pucminas/cd/aco/round/RoundCoordinator.java)

### Responsabilidade
Papel de líder: dispara as rodadas (`StartRound`), espera o `RoundDone` de todos os nós vivos
e acompanha a melhor rota global. Para assim que o nó deixa de ser líder.

### Quem cria / quem usa
- Criado por `Node`; recebe o trabalhador via `attach(worker)`.
- O listener de troca de líder em `Node` chama `onBecameLeader()`.
- `NodeServiceImpl` e `RoundWorker` chamam `onRoundDone(...)`.

### Estado
| Campo | Significado |
|---|---|
| `ROUND_TIMEOUT_MS = 30_000` | Tempo máximo esperando os `RoundDone` de uma rodada |
| `POLL_MS = 500` | Intervalo de reavaliação durante a espera |
| `isLeader` | `BooleanSupplier` ligado a `BullyElection::isLeader` |
| `running` | `AtomicBoolean`: loop em execução? Evita dois loops |
| `finished` | `AtomicBoolean`: concluiu todas as rodadas como líder |
| `done` | `ConcurrentHashMap<rodada, Set<id>>`: quem já terminou cada rodada |
| `executor` | Uma única thread para o loop |

### Métodos
| Método | Thread | O que faz |
|---|---|---|
| `void attach(RoundWorker w)` | montagem | Liga o trabalhador local. |
| `void onBecameLeader()` | thread que trocou o líder | Se não estiver rodando, submete `loop()`. |
| `void onRoundDone(int round, int from, Tour t)` | gRPC ou thread da rodada | Marca `from` como concluído, oferece `t` ao `BestTracker` e acorda a espera (`notifyAll`). |
| `boolean isFinished()` | qualquer | Lê `finished`. |
| `void close()` | `Node.close` | Encerra o executor. |

### O loop do líder
1. Dorme `START_DELAY_MS` (peers subindo, eleição estabilizando).
2. `round = worker.lastRound() + 1` (novo líder continua de onde o anterior parou).
3. Enquanto for líder e `round <= ROUNDS`:
   - participantes = vivos + ele mesmo;
   - `network.broadcastStartRound(round)` (assíncrono) e `worker.onStartRound(round, cfg.nodeId())` (local);
   - `awaitRound`: em `synchronized (this)`, a cada `wait(500)` calcula
     `pendentes = participantes − concluídos ∩ vivos`; sai quando vazio, se deixou de ser líder, ou após 30 s.
4. Ao passar de `ROUNDS`: `finished = true` e loga `FIM` com a melhor rota (ou "nenhuma rota recebida").

### Detalhes e decisões
- **Nó que cai no meio da rodada não trava o líder:** `pendentes ∩ vivos` o descarta assim que o `PeerNetwork` o marca como inacessível.
- **Rodada perdida na troca de líder é pulada**, pois o novo líder começa de `lastRound + 1`.
- **`FIM` pode aparecer de novo** se um novo líder for eleito depois do fim: `lastRound + 1 > ROUNDS` vai direto ao log final.

---

## RoundWorker

Código: [`RoundWorker.java`](../../src/main/java/br/pucminas/cd/aco/round/RoundWorker.java)

### Responsabilidade
Papel de trabalhador, presente em **todo** nó (inclusive no líder). A cada rodada:
formigas locais → entra na seção crítica → atualiza e propaga o feromônio → sai → avisa o líder.

### Quem cria / quem usa
- Criado por `Node`.
- `RoundCoordinator` chama `onStartRound` (local) e `lastRound`; `NodeServiceImpl` chama `onStartRound` e `onPheromoneUpdate` (remoto).

### Estado
| Campo | Significado |
|---|---|
| `executor` | `newSingleThreadExecutor`: **uma rodada por vez**, na ordem em que chegaram |
| `lastRound` | `AtomicInteger`: maior rodada vista (por `StartRound` ou `UpdatePheromone`) |
| `coordinator` | recebido em `onStartRound`: é para ele que o `RoundDone` volta |
| `localSink` | O `RoundCoordinator` do próprio nó |

### Métodos
| Método | Thread | O que faz |
|---|---|---|
| `int lastRound()` | qualquer | Lê `lastRound`. |
| `void onStartRound(int round, int coordinator)` | gRPC ou loop do líder | Atualiza `lastRound` (máximo) e enfileira `runRound(round, coordinator)`. Rodada repetida não refaz o trabalho, só responde. Retorna na hora. |
| `void onPheromoneUpdate(int round, double[] tau, Tour senderBest)` | gRPC | Atualiza `lastRound`, `pheromone.replace(tau)` e oferece a melhor do remetente. |
| `void close()` | `Node.close` | Encerra o executor. |

### `runRound(round)` passo a passo
1. Semente: `TSP_SEED * 1_000_003 + round * 101 + nodeId` (diferente por nó e por rodada, reprodutível).
2. `colony.runAnts(pheromone.snapshot(), ANTS, seed)` → melhor local; loga `melhor local`.
3. `mutex.acquire()` → loga `ENTROU na seção crítica`.
4. `pheromone.evaporateAndDeposit(local, ρ)`, `best.offer(local)`.
5. `network.broadcastPheromone(round, flatten(), best.get(), coordinator)` — **síncrono**: espera a confirmação de todos (o coordenador é sempre tentado; peers em quarentena são pulados).
6. `finally`: loga `SAIU da seção crítica` **e só então** chama `mutex.release()`. A ordem importa: depois do `release()` outro nó pode entrar e logar `ENTROU`; logando antes, os logs nunca mostram uma sobreposição falsa.
7. `reportDone`: se o líder sou eu, `localSink.onRoundDone(...)`; se há líder (`> 0`), `network.sendRoundDone(...)`; senão não avisa.
8. Erros: `InterruptedException` restaura a flag; `RuntimeException` é logada (`Erro na rodada ...`) sem derrubar o executor.

### Detalhes e decisões
- **Broadcast dentro da SC:** quem entrar depois já recebeu a matriz nova antes do REPLY que o libera.
  Por isso as réplicas convergem ao fim de cada rodada.
- **RoundDone só depois da SC:** quando o líder recebe todos os `RoundDone`, todas as atualizações da rodada já foram propagadas.
- **Single thread:** se um `StartRound` chegar enquanto outra rodada ainda roda, ele espera na fila em vez de rodar em paralelo.
