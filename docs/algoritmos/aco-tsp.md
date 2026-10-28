# ACO para o TSP: o problema e o algoritmo

> Material de estudo do grupo. Não copie este texto para o relatório: o enunciado desconsidera texto gerado automaticamente.

Este documento explica **o que** o sistema resolve (o Caixeiro-Viajante) e **como** resolve
(Otimização por Colônia de Formigas), e mostra onde cada ideia está no código (`src/main/java/br/pucminas/cd/aco/aco/`).
A parte distribuída (rodadas, exclusão mútua, eleição) está em [`../rodadas.md`](../rodadas.md) e nos outros
documentos de [`./`](./).

---

## 1. O Problema do Caixeiro-Viajante (TSP)

**Definição.** Dadas *n* cidades e a distância *d(i, j)* entre cada par, encontrar a rota fechada
que visita cada cidade exatamente uma vez e volta à origem com o **menor comprimento total**.

Formalmente, procuramos uma permutação π das cidades que minimize

```
L(π) = Σ_{k=0}^{n-1} d(π_k, π_{(k+1) mod n})
```

O termo `(k+1) mod n` fecha o ciclo (da última cidade de volta à primeira). É exatamente o que
`TspInstance.tourLength` faz.

**Por que é difícil.** O TSP de decisão ("existe rota com comprimento ≤ K?") é NP-completo, e a versão
de otimização é NP-difícil. O espaço de busca tem (n−1)!/2 rotas distintas no caso simétrico: para
60 cidades isso é da ordem de 10⁸⁰. Não se conhece algoritmo polinomial exato, então usamos uma
**meta-heurística**: não garante o ótimo, mas encontra rotas boas em tempo razoável.

### 1.1 Variante usada: simétrico euclidiano (EUC_2D)

- Cada cidade é um ponto (x, y) no plano.
- `d(i, j) = d(j, i)` = distância euclidiana **arredondada para o inteiro mais próximo**
  (`Math.rint(Math.hypot(dx, dy))` em `TspInstance.fromCoords`). Esse arredondamento é a convenção
  `EUC_2D` da TSPLIB; com ele os comprimentos batem com os valores ótimos publicados
  (ex.: `berlin52` tem ótimo 7542).
- A matriz de distâncias é pré-calculada uma vez (n×n) e só lida depois.

### 1.2 De onde vêm as instâncias

| Fonte | Como ativar | Código |
|---|---|---|
| Aleatória | `TSP_CITIES` (padrão 60) e `TSP_SEED` (padrão 42) | `TspInstance.random` |
| Arquivo TSPLIB | `TSP_FILE=/caminho/berlin52.tsp` | `TspInstance.fromTsplib` |

- **Aleatória:** cidades uniformes em [0, 1000)², geradas com `java.util.Random(seed)`. A mesma
  semente gera a mesma instância em qualquer máquina, o que é **obrigatório**: todos os nós precisam
  resolver o mesmo problema, e a instância não trafega pela rede.
- **TSPLIB:** o leitor procura `NODE_COORD_SECTION`, lê linhas `id x y` até `EOF` e ignora o resto
  do cabeçalho. Só suporta coordenadas (não matrizes explícitas).

### 1.3 Linha de base: vizinho mais próximo

`TspInstance.nearestNeighborLength` parte da cidade 0 e vai sempre para a cidade não visitada mais
próxima. É O(n²), guloso e normalmente fica 20–25% acima do ótimo. Serve para duas coisas:

1. **Referência** no log de início (`vizinho mais próximo = ...`): o ACO deve terminar abaixo dela.
2. **Inicializar o feromônio** (τ₀, ver §2.5).

---

## 2. Otimização por Colônia de Formigas (ACO)

### 2.1 Intuição biológica

Formigas reais depositam **feromônio** no caminho. Caminhos curtos são percorridos mais vezes por
unidade de tempo, acumulam mais feromônio e atraem mais formigas (realimentação positiva). O
feromônio também **evapora**, o que faz caminhos ruins serem esquecidos (realimentação negativa).
O ACO (Dorigo, 1992) copia esse mecanismo: formigas artificiais constroem soluções de forma
probabilística, guiadas por uma memória coletiva (a matriz de feromônio τ).

### 2.2 Variante implementada

A base é o **Ant System (AS)** com duas ideias do **MAX-MIN Ant System (MMAS)**:

| Aspecto | Escolha no código | Origem |
|---|---|---|
| Regra de transição | τ^α · η^β, roleta | AS |
| Quem deposita | só a **melhor rota** da rodada (do nó) | MMAS |
| Valor inicial τ₀ | 1 / (ρ · L_nn) | MMAS |
| Limite inferior | `TAU_MIN = 1e-9` | inspirado no τ_min do MMAS (aqui só evita zero) |
| Limite superior τ_max | não há | — |

### 2.3 Construção de uma rota (regra de transição)

Cada formiga (`AntColony.buildTour`):

1. Escolhe uma cidade inicial aleatória.
2. Estando na cidade *i*, escolhe a próxima *j* entre as não visitadas com probabilidade

```
            τ_ij^α · η_ij^β
p_ij = ─────────────────────────      η_ij = 1 / d_ij
        Σ_{k não visitada} τ_ik^α · η_ik^β
```

3. Repete até visitar todas; o comprimento é calculado por `TspInstance.tourLength`.

- **τ_ij** (feromônio): o que a colônia "aprendeu" sobre a aresta i→j.
- **η_ij** (visibilidade/heurística): arestas curtas são mais atraentes a priori.
- **α** pondera o aprendizado coletivo, **β** pondera a gulodice.

**Otimização: `etaPow` pré-calculado.** η^β não muda durante a execução, então o construtor de
`AntColony` calcula `etaPow[i][j] = (1 / max(d_ij, 1e-9))^β` uma vez. O `max(…, 1e-9)` evita divisão
por zero quando duas cidades coincidem (e na diagonal i = j). Assim, no laço interno só resta
`Math.pow(tau, alpha)`.

### 2.4 Roleta (seleção proporcional)

`AntColony.roulette` sorteia `r ∈ [0, total)` e percorre as candidatas subtraindo os pesos; a
primeira que faz `r ≤ 0` é a escolhida. Cada cidade ocupa uma "fatia" proporcional ao peso.

**Fallback de arredondamento:** por erro de ponto flutuante, a soma dos pesos subtraídos pode não
zerar `r` exatamente. Por isso o método guarda a última candidata não visitada (`last`) e a devolve
se o laço terminar. Sem isso, a formiga poderia devolver −1 e quebrar a rota.

### 2.5 Atualização do feromônio

Feita por `PheromoneMatrix.evaporateAndDeposit(tour, rho)`, **sempre dentro da seção crítica distribuída**:

1. **Evaporação** em todas as arestas: `τ_ij ← max(TAU_MIN, τ_ij · (1 − ρ))`.
2. **Depósito** na melhor rota local *T* de comprimento *L*: para cada aresta (a, b) de *T*,
   `τ_ab += 1/L` e `τ_ba += 1/L` (matriz simétrica).

Rotas mais curtas depositam mais (1/L maior). `TAU_MIN` impede que τ chegue a zero, o que tornaria
uma aresta impossível de ser escolhida para sempre.

**τ₀ = 1 / (ρ · L_nn)** (em `Node`): é o valor de equilíbrio aproximado do MMAS quando uma rota de
comprimento L_nn é reforçada a cada iteração. Começar perto da escala certa evita que as primeiras
rodadas sejam dominadas por τ inicial muito alto (exploração lenta) ou muito baixo (convergência prematura).

### 2.6 Parâmetros e seus efeitos

| Variável | Padrão | Efeito de aumentar |
|---|---|---|
| `ALPHA` (α) | 1.0 | mais peso ao feromônio: converge mais rápido, risco de estagnar |
| `BETA` (β) | 3.0 | mais guloso (prefere arestas curtas); β=0 ignora distâncias |
| `RHO` (ρ) | 0.1 | esquece mais rápido; ρ alto = pouca memória, ρ baixo = convergência lenta |
| `ANTS` | 40 | mais formigas por nó por rodada: melhor amostragem, mais CPU por rodada |
| `ROUNDS` | 30 | mais iterações globais |

Com 4 nós e `ANTS=40`, cada rodada global tem 160 formigas no total.

---

## 3. Paralelismo dentro do nó

`AntColony.runAnts` roda as formigas com `IntStream.range(0, ants).parallel()`, que usa o
`ForkJoinPool` comum (um worker por núcleo). Cada formiga é independente: lê uma **cópia** da
matriz (`PheromoneMatrix.snapshot`) e escreve só nos próprios arrays. Não há estado compartilhado
mutável, então não há lock. Ao final, `.min(comparingDouble(Tour::length))` fica com a melhor.

Há, portanto, **dois níveis de paralelismo**: entre máquinas (nós do cluster) e entre núcleos (formigas).

### 3.1 Aleatoriedade determinística

Cada formiga recebe seu próprio `SplittableRandom` com semente derivada de:

```
semente_da_rodada = TSP_SEED · 1_000_003 + rodada · 101 + nodeId      (RoundWorker.runRound)
semente_da_formiga = semente_da_rodada · 31 + índice_da_formiga       (AntColony.runAnts)
```

- Nós diferentes e rodadas diferentes exploram sequências diferentes.
- A ordem de execução das threads não importa: cada formiga tem a sua semente.
- `SplittableRandom` é mais rápido que `Random` e não tem contenção entre threads.

Observação: a execução distribuída como um todo **não** é totalmente reprodutível, porque a ordem em
que os nós entram na seção crítica muda a matriz que cada um lê na rodada seguinte.

---

## 4. Do ACO sequencial ao distribuído

No ACO clássico há um laço único: *construir rotas → atualizar τ → repetir*. Aqui o modelo é de
**várias colônias cooperando por uma matriz global replicada**:

| ACO sequencial | Este sistema |
|---|---|
| uma colônia, uma matriz τ | uma colônia por nó; cada nó tem uma **réplica** de τ |
| uma atualização por iteração | uma atualização **por nó** por rodada (cada um deposita a sua melhor local) |
| atualização trivialmente atômica | atualização protegida por **Ricart-Agrawala** + broadcast da matriz |
| iteração | rodada, disparada pelo **líder** (Bully) |

Fluxo de uma rodada em um nó (`RoundWorker.runRound`):

1. Formigas sobre `snapshot()` da réplica local (sem lock distribuído: só leitura).
2. `mutex.acquire()` → evapora + deposita a melhor local → `broadcastPheromone` (síncrono) → `release()`.
3. Envia `RoundDone` ao líder.

Consequências:
- Em uma rodada com *k* nós, a matriz recebe *k* evaporações e *k* depósitos, em ordem serializada
  pela seção crítica. Cada nó atualiza sobre a matriz que já contém os depósitos de quem entrou antes.
- Como todos começaram a rodada lendo a matriz antiga, as formigas de uma mesma rodada não se
  beneficiam umas das outras; o aprendizado é compartilhado **entre rodadas**.
- Sem a exclusão mútua ocorreria *lost update*: ver `PheromoneMatrixTest` e
  [`./ricart-agrawala.md`](./ricart-agrawala.md).

---

## 5. Mapa do código (`aco/`)

| Classe | Responsabilidade |
|---|---|
| `TspInstance` | instância (distâncias pré-calculadas), leitura TSPLIB, comprimento de rota, vizinho mais próximo |
| `Tour` | `record` imutável: ordem das cidades + comprimento |
| `AntColony` | formigas: regra de transição, roleta, paralelismo, `etaPow` |
| `PheromoneMatrix` | réplica local de τ: `snapshot`, `evaporateAndDeposit`, `flatten`/`replace` para a rede |
| `BestTracker` | melhor rota global conhecida pelo nó (`offer` só aceita se for menor) |

Testes: `AntColonyTest` (12 cidades num círculo: o ótimo é o polígono, conhecido analiticamente) e
`PheromoneMatrixTest`.

---

## 6. Perguntas prováveis na arguição

1. **Por que ACO e não força bruta?** TSP é NP-difícil; (n−1)!/2 rotas é inviável já para dezenas
   de cidades. ACO é uma meta-heurística que acha boas soluções em tempo polinomial por iteração.
2. **O que α e β controlam?** α: peso do feromônio (aprendizado coletivo). β: peso da distância
   (gulodice). β alto aproxima o vizinho mais próximo; α alto converge cedo demais.
3. **Para que serve a evaporação?** Esquecer escolhas ruins antigas e evitar estagnação.
   `TAU_MIN` impede que alguma aresta fique com probabilidade zero.
4. **Por que só a melhor rota deposita?** Ideia do MMAS: reforço mais forte nas boas soluções,
   convergência mais rápida que o AS original (onde todas depositam).
5. **Como todos os nós resolvem a mesma instância?** Mesma `TSP_SEED`/`TSP_CITIES` ou mesmo
   `TSP_FILE`; a instância nunca vai pela rede, só τ e as rotas.
6. **Onde está o paralelismo?** Entre nós (cluster) e dentro do nó (`parallel()` nas formigas,
   cada uma com cópia da matriz e `SplittableRandom` próprio).
7. **O resultado é determinístico?** As formigas de uma rodada sim (sementes fixas); a execução
   distribuída não, porque a ordem de entrada na seção crítica varia.
8. **Por que arredondar a distância?** Convenção EUC_2D da TSPLIB, para comparar com ótimos publicados.
