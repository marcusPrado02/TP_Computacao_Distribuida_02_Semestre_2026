package br.pucminas.cd.aco.aco;

import java.util.Comparator;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * Formigas locais de um nó. Cada formiga constrói uma rota escolhendo a próxima cidade
 * com probabilidade proporcional a τ^α · η^β, onde η = 1/distância.
 */
public final class AntColony {
    private final TspInstance tsp;
    private final double alpha;
    private final double beta;
    private final double[][] etaPow;

    public AntColony(TspInstance tsp, double alpha, double beta) {
        this.tsp = tsp;
        this.alpha = alpha;
        this.beta = beta;
        int n = tsp.size();
        this.etaPow = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                etaPow[i][j] = Math.pow(1.0 / Math.max(tsp.distance(i, j), 1e-9), beta);
            }
        }
    }

    /** Executa {@code ants} formigas em paralelo (núcleos da máquina) e devolve a melhor rota. */
    public Tour runAnts(double[][] tau, int ants, long seed) {
        return IntStream.range(0, ants)
                .parallel()
                .mapToObj(a -> buildTour(tau, new SplittableRandom(seed * 31 + a)))
                .min(Comparator.comparingDouble(Tour::length))
                .orElseThrow();
    }

    private Tour buildTour(double[][] tau, SplittableRandom rnd) {
        int n = tsp.size();
        int[] path = new int[n];
        boolean[] visited = new boolean[n];
        double[] weights = new double[n];
        path[0] = rnd.nextInt(n);
        visited[path[0]] = true;
        for (int step = 1; step < n; step++) {
            int from = path[step - 1];
            double total = 0;
            for (int j = 0; j < n; j++) {
                weights[j] = visited[j] ? 0 : Math.pow(tau[from][j], alpha) * etaPow[from][j];
                total += weights[j];
            }
            int next = roulette(weights, total, visited, rnd);
            path[step] = next;
            visited[next] = true;
        }
        return new Tour(path, tsp.tourLength(path));
    }

    private static int roulette(double[] weights, double total, boolean[] visited, SplittableRandom rnd) {
        double r = rnd.nextDouble() * total;
        int last = -1;
        for (int j = 0; j < weights.length; j++) {
            if (visited[j]) continue;
            last = j;
            r -= weights[j];
            if (r <= 0) return j;
        }
        return last; // erro de arredondamento: fica com a última candidata
    }
}
