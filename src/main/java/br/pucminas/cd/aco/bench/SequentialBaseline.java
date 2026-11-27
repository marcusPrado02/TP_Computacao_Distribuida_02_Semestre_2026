package br.pucminas.cd.aco.bench;

import br.pucminas.cd.aco.aco.AntColony;
import br.pucminas.cd.aco.aco.BestTracker;
import br.pucminas.cd.aco.aco.PheromoneMatrix;
import br.pucminas.cd.aco.aco.Tour;
import br.pucminas.cd.aco.aco.TspInstance;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Linha de base da análise de desempenho: o <b>mesmo trabalho</b> de um cluster de {@code NODES}
 * nós, executado numa única máquina e sem rede.
 *
 * <p>A cada rodada, para cada nó virtual {@code k = 1..NODES}: roda {@code ANTS × ITERATIONS_PER_ROUND}
 * formigas sobre o snapshot da rodada (mesma semente que o nó {@code k} usaria no cluster) e depois
 * faz os {@code NODES} depósitos em sequência — exatamente o que a seção crítica distribuída
 * serializa. Assim a comparação isola o ganho de distribuir as formigas e o custo de rede/SC.
 *
 * <p>Configuração pelas mesmas variáveis de ambiente do nó (TSP_FILE, TSP_CITIES, TSP_SEED, ROUNDS,
 * ANTS, ITERATIONS_PER_ROUND, ALPHA, BETA, RHO, ANT_THREADS) mais {@code NODES}. Saída: uma linha
 * {@code BASELINE nodes=.. rounds=.. best=.. time_ms=..} para o {@code scripts/bench.sh}.
 *
 * <pre>java -cp target/aco-node.jar br.pucminas.cd.aco.bench.SequentialBaseline</pre>
 */
public final class SequentialBaseline {
    private SequentialBaseline() {}

    /** Resultado de uma execução. */
    public record Result(int nodes, int rounds, double best, long timeMs) {}

    public static void main(String[] args) throws IOException {
        String file = System.getenv("TSP_FILE");
        TspInstance tsp = file != null && !file.isBlank()
                ? TspInstance.fromTsplib(Path.of(file))
                : TspInstance.random(intEnv("TSP_CITIES", 60), longEnv("TSP_SEED", 42));
        Result r = run(tsp, intEnv("NODES", 1), intEnv("ROUNDS", 30), intEnv("ANTS", 40),
                intEnv("ITERATIONS_PER_ROUND", 1), doubleEnv("ALPHA", 1.0), doubleEnv("BETA", 3.0),
                doubleEnv("RHO", 0.1), longEnv("TSP_SEED", 42), intEnv("ANT_THREADS", 0));
        System.out.printf(java.util.Locale.ROOT, "BASELINE nodes=%d rounds=%d best=%.0f time_ms=%d%n",
                r.nodes(), r.rounds(), r.best(), r.timeMs());
    }

    public static Result run(TspInstance tsp, int nodes, int rounds, int ants, int iterations,
                             double alpha, double beta, double rho, long seedBase, int threads) {
        PheromoneMatrix pheromone = new PheromoneMatrix(tsp.size(), 1.0 / (rho * tsp.nearestNeighborLength()));
        AntColony colony = new AntColony(tsp, alpha, beta, threads);
        BestTracker best = new BestTracker();
        long start = System.nanoTime();
        for (int round = 1; round <= rounds; round++) {
            double[][] tau = pheromone.snapshot(); // todos os nós da rodada partem da mesma geração
            Tour[] local = new Tour[nodes];
            for (int k = 1; k <= nodes; k++) {
                long seed = seedBase * 1_000_003L + round * 101L + k; // mesma fórmula do RoundWorker
                for (int it = 0; it < iterations; it++) {
                    Tour candidate = colony.runAnts(tau, ants, seed + it * 7_919L);
                    if (local[k - 1] == null || candidate.length() < local[k - 1].length()) local[k - 1] = candidate;
                }
            }
            for (int k = 1; k <= nodes; k++) { // o que a seção crítica serializa
                pheromone.evaporateAndDeposit(local[k - 1], rho, k);
                best.offer(local[k - 1]);
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        return new Result(nodes, rounds, best.get().length(), ms);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static int intEnv(String name, int fallback) {
        return Integer.parseInt(env(name, Integer.toString(fallback)));
    }

    private static long longEnv(String name, long fallback) {
        return Long.parseLong(env(name, Long.toString(fallback)));
    }

    private static double doubleEnv(String name, double fallback) {
        return Double.parseDouble(env(name, Double.toString(fallback)));
    }
}
