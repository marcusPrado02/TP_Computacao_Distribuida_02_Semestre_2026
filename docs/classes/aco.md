# Pacote `aco`

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

Voltar ao [índice](README.md) · Algoritmo: [`../algoritmos/aco-tsp.md`](../algoritmos/aco-tsp.md)

Domínio do problema: instância do TSP, formigas, matriz de feromônio e melhor rota.
Nada neste pacote sabe que existe rede; a distribuição fica em `round/` e `rpc/`.

---

## TspInstance

Código: [`TspInstance.java`](../../src/main/java/br/pucminas/cd/aco/aco/TspInstance.java)

### Responsabilidade
Representa uma instância do TSP simétrico euclidiano como matriz de distâncias `n × n`.
Sabe calcular o comprimento de uma rota e a heurística do vizinho mais próximo.

### Quem cria / quem usa
- Criada por `Node` com `random(...)` ou `fromTsplib(...)`; nos testes com `fromCoords(...)`.
- Usada por `AntColony` (distâncias) e por `Node` (`nearestNeighborLength` para τ0 e linha de base).

### Métodos
| Método | O que faz |
|---|---|
| `static TspInstance random(int n, long seed)` | `n` cidades em `[0, 1000)²` com `java.util.Random(seed)`. Mesma semente → mesma instância em todos os nós. |
| `static TspInstance fromTsplib(Path file)` | Lê `NODE_COORD_SECTION` até `EOF` (formato TSPLIB, ex.: `berlin52.tsp`). |
| `static TspInstance fromCoords(double[] x, double[] y)` | Monta a matriz com `Math.rint(Math.hypot(dx, dy))`. |
| `int size()` | Número de cidades. |
| `double distance(int i, int j)` | Distância entre `i` e `j`. |
| `double tourLength(int[] path)` | Soma das arestas, **fechando o ciclo** (última → primeira). |
| `double nearestNeighborLength()` | Rota gulosa começando na cidade 0: sempre vai à mais próxima não visitada. |

### Detalhes
- **Distâncias arredondadas (`Math.rint`)** seguem a convenção `EUC_2D` do TSPLIB, então os resultados
  são comparáveis com os ótimos publicados (ex.: berlin52 = 7542).
- Imutável após construção → thread-safe para leitura por todas as formigas em paralelo.

---

## Tour

Código: [`Tour.java`](../../src/main/java/br/pucminas/cd/aco/aco/Tour.java)

`record Tour(int[] cities, double length)`: ordem das cidades e comprimento total da rota fechada.

- Criado por `AntColony.buildTour`, por `Protos.tour` (ao receber da rede) e em testes.
- **Cuidado:** `record` com array não faz cópia defensiva nem compara conteúdo em `equals`.
  No código, ninguém modifica `cities()` depois de criado; trate como somente leitura.

---

## AntColony

Código: [`AntColony.java`](../../src/main/java/br/pucminas/cd/aco/aco/AntColony.java)

### Responsabilidade
As formigas locais de um nó. Cada formiga constrói uma rota completa escolhendo a próxima cidade com
probabilidade proporcional a **τ^α · η^β**, com η = 1/distância. Devolve a melhor rota entre as formigas.

### Quem cria / quem usa
- Criada por `Node` com `(tsp, α, β)`.
- `RoundWorker.runRound` chama `runAnts(pheromone.snapshot(), ANTS, seed)` uma vez por rodada.

### Estado
| Campo | Significado |
|---|---|
| `tsp`, `alpha`, `beta` | Instância e parâmetros |
| `etaPow[i][j]` | `(1 / max(d_ij, 1e-9))^β`, **pré-calculado** no construtor |

### Métodos
| Método | O que faz |
|---|---|
| `Tour runAnts(double[][] tau, int ants, long seed)` | Roda `ants` formigas com `IntStream.parallel()` (usa os núcleos da máquina) e retorna a de menor comprimento. A formiga `a` usa `new SplittableRandom(seed * 31 + a)`. |
| `buildTour` (privado) | Começa numa cidade aleatória; a cada passo calcula os pesos das não visitadas e sorteia por roleta. |
| `roulette` (privado) | Sorteia `r ∈ [0, total)` e subtrai pesos até `r <= 0`; por erro de arredondamento, cai na última candidata. |

### Detalhes
- **`etaPow` pré-calculado:** `Math.pow` é caro e η não muda; só τ muda entre rodadas.
- **`max(d, 1e-9)`:** evita divisão por zero se duas cidades coincidirem.
- **Semente determinística por formiga:** a execução é reprodutível, independente da ordem em que as threads rodam.
- **Thread-safety:** `runAnts` só lê `tau` (uma cópia) e `etaPow`; cada formiga tem seus próprios arrays locais.

---

## PheromoneMatrix

Código: [`PheromoneMatrix.java`](../../src/main/java/br/pucminas/cd/aco/aco/PheromoneMatrix.java)

### Responsabilidade
Réplica **local** da matriz de feromônio **global**. Toda escrita global (evaporar + depositar + propagar)
acontece dentro da seção crítica distribuída; as outras réplicas recebem a matriz pronta via `replace`.

### Quem cria / quem usa
- Criada por `Node` com `τ0 = 1 / (ρ · L_nn)` em todas as posições.
- `RoundWorker`: `snapshot()` antes das formigas; `evaporateAndDeposit` e `flatten` dentro da SC; `replace` ao receber `UpdatePheromone`.
- `Node.pheromone()` usa `flatten()` para observação.

### Métodos (todos `synchronized`)
| Método | O que faz |
|---|---|
| `double[][] snapshot()` | Cópia profunda, para as formigas lerem sem segurar o lock. |
| `void evaporateAndDeposit(Tour tour, double rho)` | `τ_ij ← max(TAU_MIN, τ_ij · (1 − ρ))` em toda a matriz; depois soma `1/L` nas arestas da rota, **nos dois sentidos** (TSP simétrico). |
| `double[] flatten()` | Matriz em *row-major* (`n*n`) para enviar pelo protobuf. |
| `void replace(double[] flat)` | Substitui a réplica; rejeita tamanho diferente de `n*n` com `IllegalArgumentException`. |

### Detalhes
- **`TAU_MIN = 1e-9`:** impede que o feromônio chegue a zero (a aresta ficaria impossível de escolher para sempre).
- **Por que `snapshot` e não ler direto?** As formigas levam tempo; segurar o lock nesse período bloquearia
  o `replace` vindo da rede (thread gRPC).
- **Por que precisa de exclusão mútua?** Ver `PheromoneMatrixTest`: sem ela, duas atualizações concorrentes se
  sobrescrevem (*lost update*) e as réplicas divergem.

---

## BestTracker

Código: [`BestTracker.java`](../../src/main/java/br/pucminas/cd/aco/aco/BestTracker.java)

### Responsabilidade
Guarda a melhor rota global conhecida pelo nó (a de menor comprimento).

### Quem usa
- `RoundWorker` oferece a melhor local (na SC) e a melhor do remetente (em `UpdatePheromone`).
- `RoundCoordinator` oferece as rotas de `RoundDone` e lê a melhor para os logs e o `FIM`.
- `PeerNetwork.broadcastPheromone` envia `best.get()` junto com a matriz.

### Métodos (`synchronized`)
| Método | O que faz |
|---|---|
| `boolean offer(Tour t)` | Aceita se `t != null` e for menor que a atual; retorna se melhorou. |
| `Tour get()` | Melhor atual, ou `null`. |
