# Rodadas: como o líder coordena o trabalho

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

Uma **rodada** é uma iteração global do ACO: todos os nós rodam suas formigas, cada um atualiza
a matriz global de feromônio (um de cada vez) e avisa o líder. O líder só começa a próxima rodada
quando todos os nós vivos terminaram. Código em `src/main/java/br/pucminas/cd/aco/round/`.

Pré-requisitos: [`./algoritmos/aco-tsp.md`](./algoritmos/aco-tsp.md) (o que as formigas fazem),
[`./algoritmos/bully.md`](./algoritmos/bully.md) (quem é o líder) e
[`./algoritmos/ricart-agrawala.md`](./algoritmos/ricart-agrawala.md) (seção crítica).

---

## 1. Papéis

Todos os nós executam o mesmo programa. Cada nó tem **sempre** um trabalhador e **às vezes** é líder.

| Papel | Classe | Quem | Faz o quê |
|---|---|---|---|
| Trabalhador | `RoundWorker` | todos os nós (inclusive o líder) | formigas → seção crítica → `RoundDone` |
| Coordenador | `RoundCoordinator` | só o nó eleito | `StartRound`, espera `RoundDone`, decide o fim |
| Destino do aviso | `RoundSink` (interface) | implementada por `RoundCoordinator` | recebe "nó X terminou a rodada k" |

**Por que a interface `RoundSink`?** O líder também é trabalhador. Quando o trabalhador local
termina, ele não precisa mandar um RPC para si mesmo: chama `localSink.onRoundDone(...)` direto
(`RoundWorker.reportDone`). Os outros nós chegam pelo RPC `RoundDone`, que `NodeServiceImpl.roundDone`
também entrega ao mesmo `RoundSink`. A interface também quebra a dependência circular
coordenador ↔ trabalhador (montada em `Node` com `coordinator.attach(worker)`).

---

## 2. Ciclo de uma rodada

```mermaid
sequenceDiagram
    participant L as Líder N3 (coordenador + trabalhador)
    participant A as N1
    participant B as N2

    Note over L: RoundCoordinator.loop
    L-)A: StartRound(k)  (assíncrono)
    L-)B: StartRound(k)
    Note over L: worker.onStartRound(k) local

    par formigas em paralelo
        Note over A: runAnts(snapshot)
        Note over B: runAnts(snapshot)
        Note over L: runAnts(snapshot)
    end

    Note over A,L: Ricart-Agrawala serializa as atualizações
    Note over A: SC: evapora + deposita
    A->>B: UpdatePheromone(matriz) (espera ack)
    A->>L: UpdatePheromone(matriz) (espera ack)
    Note over A: release()
    A->>L: RoundDone(k, melhor local)

    Note over B: SC: atualiza sobre a matriz de A
    B->>A: UpdatePheromone
    B->>L: UpdatePheromone
    B->>L: RoundDone(k, ...)

    Note over L: SC do líder; RoundDone local via RoundSink
    Note over L: pending vazio → rodada k concluída → k+1
```

Passo a passo:

1. **Líder** (`RoundCoordinator.loop`): monta `participants` = peers vivos + ele mesmo, cria o
   conjunto `done[k]`, chama `network.broadcastStartRound(k)` (envio assíncrono, um virtual thread
   por peer) e `worker.onStartRound(k)` para o próprio trabalhador.
2. **Cada trabalhador** (`RoundWorker.onStartRound`): atualiza `lastRound` e enfileira `runRound(k)`
   no seu executor de **uma thread só**, o que garante uma rodada por vez por nó.
3. `runRound`: formigas sobre `pheromone.snapshot()`, depois `mutex.acquire()`, depois na SC
   `evaporateAndDeposit` + `best.offer(local)` + `network.broadcastPheromone(...)`; depois
   `mutex.release()` no `finally`.
4. `reportDone`: se o líder sou eu, `localSink.onRoundDone`; senão `network.sendRoundDone(leader, ...)`.
   Se ainda não há líder (`leaderId <= 0`), o aviso é descartado.
5. **Líder** (`onRoundDone`): marca o remetente em `done[k]`, oferece a rota ao `BestTracker` e
   acorda `awaitRound` com `notifyAll()`.

---

## 3. Quando a rodada termina

`RoundCoordinator.awaitRound(round, participants)` repete, a cada `POLL_MS = 500` ms (ou antes, se
acordado por `notifyAll`):

```
pending = participants − done[round]          // quem ainda não avisou
pending = pending ∩ (vivos ∪ {eu})             // ignora quem caiu
se pending vazio → rodada concluída
```

Saídas possíveis:

| Situação | Resultado |
|---|---|
| todos os vivos mandaram `RoundDone` | rodada concluída normalmente |
| um participante caiu | sai de `pending` quando a rede o marca como inacessível |
| passaram `ROUND_TIMEOUT_MS = 30_000` | log "rodada k encerrada por timeout" e segue |
| este nó deixou de ser líder | o laço para (`isLeader` falso) |

**Por que o polling, se já há `notifyAll`?** Porque a mudança no conjunto de vivos (um peer que
caiu) não gera notificação; ela é descoberta a cada consulta a `network.alivePeers()`.

Nós que **entram** no meio de uma rodada não estão em `participants` dela; passam a participar a
partir da próxima, quando o `StartRound` chega até eles e os marca vivos de novo.

---

## 4. Início e fim

- **`START_DELAY_MS`** (padrão 5000): o loop do coordenador dorme esse tempo antes da primeira
  rodada. Dá tempo dos outros processos subirem e da eleição estabilizar (ex.: N1 se elege
  primeiro e logo depois N4 sobe e toma a liderança; o loop de N1 acorda, vê que não é mais líder e sai).
- **Fim**: quando `round > ROUNDS`, o líder marca `finished`, loga
  `FIM: melhor rota = <comprimento> [cidades]` e o loop termina. Os nós continuam vivos (servidor
  gRPC ativo) até serem desligados.

---

## 5. Troca de líder no meio da execução

Quando um novo líder é eleito, o listener registrado em `Node` chama `coordinator.onBecameLeader()`,
que inicia o loop (se ainda não estiver rodando, via `AtomicBoolean running`).

- **De onde continuar:** `round = worker.lastRound() + 1`. `lastRound` é o maior número de rodada
  que o nó já viu, em `StartRound` ou em `UpdatePheromone` (ambos fazem `accumulateAndGet(round, max)`).
- **Rodada perdida é pulada:** se o líder caiu no meio da rodada k, o novo líder começa em k+1. Os
  trabalhadores que ainda estavam em k terminam normalmente (a fila do executor é sequencial), mas o
  `RoundDone` deles pode ir para o líder antigo e se perder. Aceito na Entrega 1; documentado em
  [`./ARQUITETURA.md`](./ARQUITETURA.md) §6.
- **FIM repetido:** se o líder cai **depois** de concluir todas as rodadas, o novo líder calcula
  `lastRound + 1 > ROUNDS`, não executa nada e imprime `FIM` de novo com a melhor rota que ele
  conhece. Isso é esperado e até útil: confirma que o resultado sobreviveu à queda.
- Também há o `START_DELAY_MS` antes do novo líder começar.

---

## 6. Melhor rota global (`BestTracker`)

Cada nó tem um `BestTracker` (`synchronized`) que só aceita uma rota se ela for menor que a atual.
Ele é alimentado por três caminhos:

1. A melhor local do próprio nó, dentro da SC (`RoundWorker.runRound`).
2. A melhor global do remetente, que vem junto do `UpdatePheromone` (`best_tour`, `best_length`).
3. No líder, as rotas dos `RoundDone`.

Por isso, depois de uma rodada, todos os nós tendem a conhecer a mesma melhor rota, e o novo líder
após uma falha consegue reportar o resultado.

---

## 7. Por que o broadcast da matriz é síncrono dentro da SC

`PeerNetwork.broadcastPheromone` envia a matriz a todos os peers em paralelo e **espera todos os acks**
(ou o deadline de 2 s) antes de retornar; só então `release()` é chamado.

Raciocínio:
1. Quem entra na SC depois precisa ter recebido `REPLY` de quem saiu.
2. Quem saiu só envia os `REPLY`s adiados em `release()`.
3. `release()` só acontece depois que todos confirmaram a nova matriz.
4. Logo, quando o próximo nó entra na SC, a réplica dele **já** contém a atualização anterior.

Isso garante que a sequência de atualizações é a mesma em todas as réplicas e que, ao final da
rodada, todas as réplicas são **idênticas** (verificado em
`ClusterIntegrationTest.elegeMaiorIdExecutaTodasAsRodadasEReplicasConvergem`).

A exceção é falha de rede: se o ack de algum peer não chega em 2 s, o erro é só logado e aquele peer
pode ficar com réplica desatualizada. Tratar isso é escopo da Entrega 2 (controle de réplicas).

---

## 8. Mapa do código (`round/`)

| Método | Thread | O que faz |
|---|---|---|
| `RoundCoordinator.onBecameLeader` | quem notificou a eleição | inicia `loop` no executor do coordenador |
| `RoundCoordinator.loop` | thread única do coordenador | laço das rodadas |
| `RoundCoordinator.awaitRound` | idem | espera `RoundDone` (wait/notify + polling) |
| `RoundCoordinator.onRoundDone` | thread do gRPC ou do trabalhador local | registra e acorda o loop |
| `RoundWorker.onStartRound` | thread do gRPC (ou do coordenador local) | enfileira a rodada |
| `RoundWorker.runRound` | thread única do trabalhador | formigas + SC + aviso |
| `RoundWorker.onPheromoneUpdate` | thread do gRPC | substitui a réplica e atualiza a melhor rota |

---

## 9. Perguntas prováveis na arguição

1. **Por que precisa de líder se o algoritmo é simétrico?** Alguém tem que decidir quando a rodada
   acabou e disparar a próxima; sem isso os nós sairiam de sincronia. O Bully escolhe esse alguém.
2. **O líder também roda formigas?** Sim, todo nó é trabalhador; o líder só tem o papel extra.
3. **Como o líder sabe que a rodada acabou?** Quando todos os participantes ainda vivos mandaram
   `RoundDone`, ou após 30 s de timeout.
4. **E se um nó cair no meio?** Sai do conjunto de vivos na próxima chamada que falhar e deixa de ser esperado.
5. **E se o líder cair?** Os seguidores detectam por heartbeat, elegem outro, e ele continua de
   `lastRound + 1`; a rodada interrompida é pulada.
6. **Por que o broadcast da matriz é síncrono?** Para que quem entra na SC depois sempre veja a
   matriz mais recente; assim as réplicas convergem.
7. **Por que `START_DELAY_MS`?** Para os nós subirem e a eleição estabilizar antes da primeira rodada.
8. **Por que o novo líder imprime FIM de novo?** Porque `lastRound + 1 > ROUNDS`: não há rodada a
   executar, ele só reporta a melhor rota conhecida.
