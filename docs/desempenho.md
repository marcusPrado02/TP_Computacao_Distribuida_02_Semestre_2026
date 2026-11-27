# Análise de desempenho — cluster × execução em uma máquina

> Documento interno com as medições e o método. **Não é o texto do relatório**: os números e
> gráficos podem ir para o relatório, mas a análise escrita lá é do grupo.

## 1. O que se compara

| Lado | O que roda |
|---|---|
| **Cluster** | N processos `aco-node` (gRPC, Bully, Ricart-Agrawala, réplicas versionadas). Tempo medido pelo líder, da 1ª à última rodada (`LÍDER: R rodada(s) em T ms`). |
| **Sequencial** | `bench.SequentialBaseline`: **o mesmo algoritmo** numa única JVM, sem rede. Em cada rodada, para cada nó virtual k = 1..N, roda as formigas de k sobre o snapshot da rodada (mesma semente que o nó k usaria) e depois faz os N depósitos em sequência, exatamente o que a seção crítica distribuída serializa. |

Como o algoritmo é o mesmo, a diferença de tempo isola duas coisas: o ganho de rodar as formigas
dos N nós em paralelo e o custo de rede + seção crítica.
**Prova de que é o mesmo algoritmo:** com N = 1 e N = 2, a melhor rota do cluster e a da linha de
base saem idênticas (10052 e 9623 no teste curto; 8046 no modo fraco com N = 1).

Dois cenários:

- **Escalabilidade fraca (`MODE=weak`)**: cada nó roda `ANTS` formigas, então o trabalho total cresce
  com N. Pergunta: "N máquinas fazem N vezes mais trabalho no mesmo tempo?"
  `speedup = T_sequencial(mesmo trabalho) / T_cluster(N)`.
- **Escalabilidade forte (`MODE=strong`)**: total fixo de `ANTS_TOTAL` formigas por rodada, dividido
  entre os N nós. Pergunta: "o mesmo trabalho termina N vezes mais rápido?"
  `speedup = T_sequencial(1 nó, todas as formigas) / T_cluster(N)`.

## 2. Ambiente e parâmetros

- Uma máquina: AMD Ryzen 7 5825U (8 núcleos físicos, 16 threads), OpenJDK 21.0.4, Linux.
- Para imitar **N máquinas de 2 núcleos**, cada nó usa `ANT_THREADS=2` e a linha de base também usa
  2 threads. Com N = 4 são 8 threads, uma por núcleo físico, sem disputa de SMT (ver §5).
- Instância `data/berlin52.tsp` (ótimo conhecido 7542), 20 rodadas, `ITERATIONS_PER_ROUND=500`.
- Fraco: 40 formigas/nó (20 000 formigas/nó/rodada). Forte: 160 formigas/rodada no total (80 000 com as iterações).
- Mediana de 3 repetições. Comando: `MODE=weak REPS=3 ROUNDS=20 ITERS=500 ANT_THREADS=2 scripts/bench.sh`
  (e `MODE=strong ... ANTS_TOTAL=160`). Saídas brutas em `docs/desempenho/`.

## 3. Resultados

### Escalabilidade fraca (trabalho cresce com N)

| Nós | Formigas/rodada | Cluster (ms) | Sequencial (ms) | Speedup | Eficiência | ms/rodada (cluster) |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 20 000 | 5 180 | 5 108 | 0,99 | 0,99 | 259 |
| 2 | 40 000 | 6 336 | 10 797 | 1,70 | 0,85 | 317 |
| 3 | 60 000 | 7 102 | 15 973 | 2,25 | 0,75 | 355 |
| 4 | 80 000 | 9 052 | 20 563 | 2,27 | 0,57 | 453 |

### Escalabilidade forte (80 000 formigas por rodada, divididas entre os nós)

| Nós | Formigas/nó/rodada | Cluster (ms) | Sequencial (ms) | Speedup | Eficiência | Melhor rota cluster | Melhor rota sequencial |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 80 000 | 20 902 | 21 020 | 1,01 | 1,01 | 7934 | 7934 |
| 2 | 40 000 | 12 632 | 20 868 | 1,65 | 0,82 | **7542** | 7934 |
| 3 | 26 500 | 10 819 | 21 324 | 1,97 | 0,66 | **7542** | 7934 |
| 4 | 20 000 | 9 623 | 21 233 | 2,21 | 0,55 | **7542** | 7934 |

(O forte com N = 3 usa 53 formigas/nó, pela divisão inteira de 160: 79 500 formigas.)

## 4. Leitura dos números

- **N = 1 custa ~1 %** em relação ao sequencial: o cluster de 1 nó não troca mensagens, e o
  overhead do framework (rodadas, seção crítica local, logs) é desprezível.
- **O speedup é real, mas sublinear.** No fraco, 4 nós fazem 4× o trabalho em 1,75× o tempo de 1 nó.
  A parte paralela (formigas) dura o mesmo em cada nó; a parte serial cresce com N:
  - a seção crítica serializa **N escritas por rodada**. Cada entrada custa 2(N − 1) mensagens do
    Ricart-Agrawala mais o broadcast síncrono da matriz 52×52 (2704 doubles) para N − 1 peers.
    Medido no log: ~10 ms por entrada com 4 nós, ou seja, ~40 ms por rodada só de SC;
  - a barreira da rodada espera o nó mais lento.
  É a lei de Amdahl: com fração serial s, o speedup é limitado a 1/s.
- **A granularidade decide.** Com 50 iterações/rodada (rodada de ~35 ms), a SC dominava e o cluster
  não ganhava nada (speedup ≈ 1,07 com 4 nós). Com 500 iterações (rodada de ~260 ms por nó), a parte
  paralela domina e aparece o ganho de 2,2×. Em instâncias maiores, onde as formigas custam O(n²) por
  rota, a fração serial cai ainda mais.
- **Qualidade da solução (forte):** com o mesmo número de formigas, o cluster chegou ao **ótimo 7542**
  e o sequencial de 1 nó parou em 7934. Não é ruído: o cluster faz N depósitos por rodada (um por nó)
  em vez de 1, então o feromônio incorpora mais rotas boas por rodada. A distribuição muda a dinâmica
  do ACO, não só o tempo. A comparação justa de qualidade é a do modo fraco, em que os dois lados fazem
  os mesmos N depósitos e chegam à mesma rota.

## 5. Ameaças à validade

- **Uma máquina, não N.** Os nós dividem memória, cache L3 e o orçamento térmico do notebook
  (processador U-series: o clock de boost cai com todos os núcleos ocupados). Isso explica parte da
  queda de eficiência em N = 4. Em máquinas físicas cada nó tem a CPU inteira e o resultado tende a ser
  melhor nas formigas, mas pior na rede: latência de LAN, que é ~0,1 ms aqui no loopback e 1–5 ms em
  Wi-Fi, multiplicada pelas 2(N − 1) mensagens por entrada na SC.
- **SMT.** A primeira medição usou 4 threads/nó (16 threads em 8 núcleos físicos) e mediu contenção, não
  distribuição: o tempo do cluster crescia quase linearmente com N. Por isso `N × ANT_THREADS` deve
  ficar ≤ núcleos *físicos* ao simular N máquinas numa só. O `bench.sh` avisa quando passa do número
  de núcleos lógicos.
- **Tempo do líder.** O cronômetro começa na 1ª rodada (exclui subida, eleição e barreira) e para no
  fim da última. A linha de base mede as mesmas rodadas.

## 6. Como reproduzir

```bash
mvn -q -DskipTests package
MODE=weak   REPS=3 ROUNDS=20 ITERS=500 ANT_THREADS=2 scripts/bench.sh
MODE=strong REPS=3 ROUNDS=20 ITERS=500 ANT_THREADS=2 ANTS_TOTAL=160 scripts/bench.sh
# ou ./tp bench (padrões do script). Em máquinas físicas: ANT_THREADS=0 (todos os núcleos)
# e um nó por máquina; o tempo sai na linha "LÍDER: ... rodada(s) em ... ms" do líder.
```

Linha de base isolada:

```bash
NODES=4 TSP_FILE=data/berlin52.tsp ROUNDS=20 ANTS=40 ITERATIONS_PER_ROUND=500 ANT_THREADS=2 \
  java -cp target/aco-node.jar br.pucminas.cd.aco.bench.SequentialBaseline
```
