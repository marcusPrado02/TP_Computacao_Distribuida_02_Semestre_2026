# Pacote raiz `br.pucminas.cd.aco`

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

Duas classes: o ponto de entrada do processo (`NodeMain`) e a montagem do nó (`Node`).
Voltar ao [índice](README.md).

---

## NodeMain

Código: [`NodeMain.java`](../../src/main/java/br/pucminas/cd/aco/NodeMain.java)

### Responsabilidade
Ponto de entrada da JVM (`main`). Lê a configuração das variáveis de ambiente, cria o `Node`,
registra o gancho de desligamento e bloqueia até o servidor gRPC terminar.
É a classe indicada como `mainClass` no `maven-shade-plugin`, por isso `java -jar aco-node.jar` a executa.

### Quem cria / quem usa
- Criada pela JVM. Não é usada por nenhuma outra classe (construtor privado).
- Usa: `NodeConfig.fromEnv()` → `Node` → `node.start()` → `node.awaitTermination()`.

### Métodos
| Método | O que faz |
|---|---|
| `static void main(String[] args)` | Fluxo completo de vida do processo (abaixo). |

Fluxo:
1. `NodeConfig.fromEnv()`. Se a configuração for inválida (`IllegalArgumentException` ou `IllegalStateException`),
   imprime `Configuração inválida: ...` em `stderr` e encerra com **código 2**, sem stack trace.
2. `new Node(cfg)` monta todos os módulos (ainda sem abrir porta).
3. Registra um *shutdown hook* (thread `"shutdown"`) que chama `node.close()`. Ele roda em `Ctrl+C`,
   `docker compose stop` (SIGTERM) ou fim normal da JVM.
4. `node.start()` abre a porta e dispara a primeira eleição.
5. `node.awaitTermination()` bloqueia a thread `main` até o servidor parar.

### Detalhes e decisões
- **Por que código 2?** Distingue "erro de configuração" (culpa de quem subiu o nó) de falha inesperada (código 1, stack trace).
- **Por que separar de `Node`?** `NodeMain` sabe de ambiente e de processo; `Node` só sabe montar componentes a
  partir de um `NodeConfig`. Isso permite que os testes de integração criem vários `Node` na mesma JVM.

---

## Node

Código: [`Node.java`](../../src/main/java/br/pucminas/cd/aco/Node.java) · Tema: [`../concorrencia.md`](../concorrencia.md)

### Responsabilidade
*Composition root*: o único lugar que conhece todas as classes concretas e as conecta.
Todos os nós são o mesmo programa; ser líder ou não é decidido em tempo de execução pelo Bully.
Também expõe alguns getters de observação usados pela demonstração e pelos testes.

### Quem cria / quem usa
- Criado por `NodeMain.main` (produção) e por `ClusterIntegrationTest` (três instâncias na mesma JVM).
- Cria e liga: `LamportClock`, `TspInstance`, `PheromoneMatrix`, `AntColony`, `BestTracker`, `PeerNetwork`,
  `BullyElection`, `LeaderMonitor`, `RicartAgrawala`, `RoundCoordinator`, `RoundWorker`, `NodeServiceImpl` e o `Server` gRPC.

### O que o construtor faz, em ordem
1. Cria o `LamportClock` e chama `Log.init(nodeId, clock::now)`.
2. Carrega o TSP: `TspInstance.fromTsplib(TSP_FILE)` se definido; senão `TspInstance.random(TSP_CITIES, TSP_SEED)`.
3. Calcula `L_nn` (vizinho mais próximo) e loga como linha de base.
4. Cria a matriz de feromônio com **τ0 = 1 / (ρ · L_nn)**.
5. Cria `AntColony(tsp, α, β)`.
6. Cria a rede e os algoritmos distribuídos: `PeerNetwork`, `BullyElection`, `LeaderMonitor`, `RicartAgrawala`.
7. Cria `RoundCoordinator` e `RoundWorker` e resolve a dependência circular entre eles:
   o trabalhador recebe o coordenador como `RoundSink`, e o coordenador recebe o trabalhador via `attach(worker)`.
8. Registra o listener `election.onLeaderChange`: quando o novo líder é este nó, chama `coordinator.onBecameLeader()`.
9. Monta o `Server` gRPC na porta `NODE_PORT` com dois interceptors:
   `TransmitStatusRuntimeExceptionInterceptor` (repassa `PERMISSION_DENIED` lançado no handler) e
   `ClusterAuth.serverInterceptor(token)` (exige o token). O servidor ainda **não** é iniciado.

### Métodos públicos
| Método | Thread | O que faz |
|---|---|---|
| `Node(NodeConfig)` | chamador | Monta tudo (acima). Pode lançar `IOException` ao ler `TSP_FILE`. |
| `void start()` | chamador | `server.start()`, loga a porta e os peers, `monitor.start()`, `election.startElection()`. |
| `void awaitTermination()` | chamador (bloqueia) | Espera o servidor gRPC desligar. |
| `int id()` | qualquer | Id deste nó. |
| `int leaderId()` | qualquer | Líder conhecido (−1 se nenhum). |
| `int lastRound()` | qualquer | Última rodada vista pelo trabalhador. |
| `boolean finishedAsLeader()` | qualquer | `true` se este nó, como líder, concluiu todas as rodadas. |
| `double[] pheromone()` | qualquer | Cópia (row-major) da réplica local da matriz. |
| `Tour best()` | qualquer | Melhor rota conhecida, ou `null`. |
| `void close()` | chamador | Desliga tudo (abaixo). |

### Ordem de desligamento (`close`)
`monitor` → `coordinator` → `worker` → `election` → `mutex` → `server.shutdownNow()` → `network.close()` →
`server.awaitTermination(5 s)`.

A ideia é **parar quem gera trabalho antes de derrubar a rede que esse trabalho usa**:
primeiro param os heartbeats e as rodadas; por último fecham os canais gRPC.

### Thread-safety
`Node` não tem estado mutável próprio: todos os campos são `final`. Os getters delegam a componentes
que já são thread-safe (atomics ou `synchronized`).

### Detalhes e decisões
- **Por que o servidor só sobe em `start()`?** Para o objeto estar totalmente montado antes de aceitar a primeira mensagem.
  Um RPC que chegasse no meio do construtor encontraria campos ainda nulos.
- **Por que getters de observação?** O teste de integração verifica propriedades globais (todos concordam no líder,
  réplicas idênticas, todas as rodadas vistas). Eles não alteram estado.
