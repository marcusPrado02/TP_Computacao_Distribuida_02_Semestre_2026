# Tratamento de falhas e controle de réplicas (Entrega 2)

> Documento interno: decisões, mecanismos e testes. **Não é o texto do relatório.**

O enunciado da Entrega 2 exige tratamento de falhas e pelo menos um requisito extra. Escolhemos
**controle de réplicas** (a matriz de feromônio é replicada em todos os nós) e **análise de
desempenho** ([`desempenho.md`](desempenho.md)). Este documento cobre a parte de falhas e réplicas.

## 1. Modelo de falhas

- **Crash-stop / crash-recovery:** um nó para (SIGKILL, queda de energia, `docker stop`) e pode voltar
  com o mesmo id e o mesmo endereço.
- **Falhas de comunicação:** mensagem perdida, RPC que estoura o deadline, canal gRPC em
  *reconnect backoff*, peer lento (GC, pico de CPU).
- **Fora do escopo:** falhas bizantinas (nó mentindo) e partição de rede duradoura com dois lados
  ativos. O detector é por timeout, e num sistema assíncrono não existe detector perfeito (FLP); o
  que fazemos é **detectar e convergir** quando o detector erra.

## 2. Falhas tratadas

| # | Falha | Antes (Entrega 1) | Agora | Onde |
|---|---|---|---|---|
| F1 | Peer momentaneamente inacessível (canal em backoff, pacote perdido) durante o pedido de SC | uma falha de RPC já contava como permissão → **dois nós na SC** (acontecia na subida do cluster) | só conta como permissão quando o detector **suspeita** do peer: sem nenhum contato bem-sucedido há `max(1,5 s, 3 × RPC_TIMEOUT_MS)`. Até lá o REQUEST é reenviado a cada 500 ms | `PeerNetwork.isSuspected`, `RicartAgrawala.acquire` |
| F2 | Peer vivo em quarentena (marcado morto há pouco) | ficava 2 s sem ser contatado, mesmo já falando conosco | **qualquer mensagem recebida** prova que ele está vivo: sai da quarentena e o canal reconecta na hora (`resetConnectBackoff`) | `PeerNetwork.markAlive`, `NodeServiceImpl.receive` |
| F3 | UPDATE de feromônio atrasado ou fora de ordem | sobrescrevia matriz nova com matriz velha | réplica **versionada**: só aplica carimbo `(versão, escritor)` maior que o local | `PheromoneMatrix.applyIfNewer`, `replica.Version` |
| F4 | Duas escritas concorrentes (exclusão mútua violada porque o detector errou) | réplicas **divergiam em silêncio** | conflito **detectado** (mesma versão, escritor diferente → log `CONFLITO`) e todas as réplicas **convergem** para o maior carimbo (*last-writer-wins* determinístico). Perde-se um depósito, mas ninguém diverge | `PheromoneMatrix.applyIfNewer` |
| F5 | Nó cai e volta | voltava com a matriz inicial (v0) e, ao escrever, apagava o progresso de todos | na subida: lê o checkpoint local e faz **`FetchState`** em todos os peers, ficando com o estado mais novo (matriz, melhor rota, última rodada) **antes** de entrar na eleição | `Node.start`, `RoundWorker.syncFromCluster` |
| F6 | Réplica atrasada (perdeu UPDATEs em quarentena) | rodava as formigas sobre matriz velha e escrevia por cima | `StartRound` leva a versão do líder; quem está atrás faz **catch-up** com o líder antes da rodada. E se o broadcast revelar um peer com versão mais nova, o escritor busca o estado dele | `RoundWorker.runRound`, `PeerNetwork.broadcastPheromone` |
| F7 | Líder cai no meio de uma rodada | o novo líder pulava para `última + 1` (rodada perdida) | o novo líder **sincroniza** com o cluster e **retoma a rodada interrompida**; quem já terminou só responde (deduplicação), quem não terminou termina | `RoundCoordinator.loop`, `RoundWorker.onStartRound` |
| F8 | Líder que reinicia | recomeçava da rodada 1 com a matriz inicial e retransmitia | sincroniza na subida (F5) e continua da maior rodada vista no cluster | idem |
| F9 | `RoundDone` perdido | o líder esperava os 30 s do timeout da rodada | até 3 tentativas com backoff (200 ms, 400 ms); se o líder morreu, desiste (o novo refaz a rodada) | `PeerNetwork.sendRoundDone` |
| F10 | RoundDone para líder que caiu durante a rodada | resultado ia para o líder morto | rodada reemitida por outro líder redireciona o `RoundDone` da rodada em andamento para ele | `RoundWorker.reportTo` |
| F11 | **Cluster inteiro** cai | tudo perdido | **checkpoint** em disco (`STATE_DIR`): gravação atômica (arquivo temporário + `rename`) e coalescida (fora da SC). Na volta, o líder continua da rodada gravada | `replica.Checkpoint` |
| F12 | Checkpoint corrompido, truncado ou de outra instância TSP | — | ignorado com log; o nó recupera dos peers. O arquivo tem impressão digital da instância (n e comprimento do vizinho mais próximo) | `Checkpoint.load` |
| F13 | Escritor e UPDATE concorrente no meio da SC | — | matriz e carimbo lidos juntos (`versioned()`): nunca se propaga uma mistura | `RoundWorker.runRound` |

### Como o carimbo de versão achou um bug antigo (F1)

Ao ligar a detecção de conflito, a suíte de testes passou a mostrar `CONFLITO ... v1` **logo na
rodada 1**, com todos os nós vivos. Investigação:

1. Na subida, cada nó fala com peers que ainda não abriram a porta (handshake, eleição, sincronização).
2. O canal gRPC entra em *reconnect backoff* (~1 s). Nesse intervalo toda chamada falha na hora com
   `UNAVAILABLE`, **com o peer já vivo**.
3. O Ricart-Agrawala contava a falha como permissão → dois nós na seção crítica.

O bug existia desde a Entrega 1, mas era invisível: as réplicas só divergiam por uma rodada e
convergiam na seguinte. Com F1 + F2, a suíte inteira roda com **0 conflitos** (antes, 23). É o
argumento central: o versionamento não substitui a exclusão mútua; ele torna as violações
**observáveis** e **inofensivas** para a consistência.

## 3. Mensagens novas (`node.proto`)

| RPC / campo | Direção | Conteúdo |
|---|---|---|
| `FetchState(StateRequest) → StateSnapshot` | qualquer nó → peer | `version`, `writer_id`, matriz, melhor rota, `last_round` |
| `UpdatePheromone → UpdateReply` | réplica → escritor | `applied` e a versão em que a réplica ficou (escritor descobre que estava atrasado) |
| `PheromoneUpdate.version/writer_id` | escritor → réplicas | carimbo da escrita |
| `RoundStart.version` | líder → seguidores | versão da réplica do líder, para catch-up |

## 4. Por que estas escolhas

- **Last-writer-wins com ordem total, e não merge.** A atualização (evaporar tudo + depositar) não
  comuta, então não há como "somar" duas escritas concorrentes corretamente. Perder um depósito em
  caso raro é aceitável para uma metaheurística (o próximo nó deposita de novo); divergir não é.
- **Versão escalar, e não vetor de versões.** As escritas são serializadas pela SC na operação
  normal, então uma versão escalar basta. O `writer_id` desempata e é o que revela a concorrência.
- **Detector por suspeita, e não "falha = morto".** Trocamos latência por segurança: um peer morto de
  verdade custa uma janela (1,5 s nos padrões) uma única vez, e depois fica em quarentena. Um peer vivo
  com o canal em backoff deixa de causar violação.
- **Sincronizar antes da eleição.** Se o nó que volta tem o maior id, ele assume na hora; precisa já
  saber a versão e a rodada, senão viraria líder com estado velho.
- **Retomar a rodada em vez de pulá-la.** Reemitir custa uma rodada curta (quem terminou só responde)
  e garante que nenhum nó perde a vez de depositar.

## 5. Limitações que continuam

- Partição de rede longa com os dois lados ativos: cada lado elege líder e avança; na reunião, o maior
  carimbo vence e o trabalho do outro lado se perde (LWW). Resolver exige quórum/consenso (ex.: Raft).
- Peer que fica sem responder a janela inteira e **continua vivo** ainda pode entrar junto na SC
  (FLP): detectado como conflito e convergido, mas um depósito se perde.
- Sem TLS: o token trafega em claro e `sender_id` pode ser forjado por quem tem o token.
- O checkpoint é por nó e local; não há replicação do arquivo.

## 6. Testes

| Teste | Cenário |
|---|---|
| `VersionedReplicaTest` (5) | versão avança; UPDATE atrasado rejeitado; matriz inicial de nó reiniciado não apaga progresso; escritas concorrentes detectadas e convergentes; ordem total dos carimbos |
| `CheckpointTest` (5) | grava/lê; sem arquivo; outra instância ignorada; arquivo truncado ignorado; gravação coalescida pega o estado mais novo |
| `SuspicionTest` (3) | falha de peer não suspeito **não** é permissão (regressão de F1); peer suspeito deixa de ser esperado; janela do `PeerNetwork` e `markAlive` |
| `FaultToleranceIntegrationTest` (3) | gRPC real, 3 nós: seguidor cai e volta já sincronizado; líder cai no meio e o novo retoma e conclui com réplicas idênticas; cluster inteiro reinicia do checkpoint e continua da rodada 8 |
| `ClusterIntegrationTest`, `BroadcastPheromoneTest`, ... | Entrega 1, continuam verdes |

Total: **59 testes** (`mvn test`), 0 conflitos de réplica nos logs da suíte.

## 7. Demonstração

```bash
./tp demo recuperacao      # seguidor cai e volta sincronizado; líder cai e o novo retoma a rodada
./tp timeline replica      # eventos de réplica em ordem de Lamport
./tp check                 # exclusão mútua nos logs

# Docker (checkpoint no volume aco-state):
docker compose up --build -d
docker compose kill node2 && sleep 10 && docker compose start node2   # volta sincronizado
docker compose kill node4                                             # N3 assume e retoma
docker compose logs | grep -E "Sincronização|retomando|CONFLITO"
docker compose down -v     # -v apaga os checkpoints
```

Saída esperada do demo (medida em 26/11): N1 cai na rodada ~8, volta, lê o checkpoint e sincroniza
para `v80`/rodada 25; N4 (líder) cai, N3 assume em ~6 s e registra `retomando da rodada 30`;
102 entradas na SC sem violação, 0 conflitos.
