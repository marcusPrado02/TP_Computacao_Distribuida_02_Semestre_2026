package br.pucminas.cd.aco.aco;

import java.util.Comparator;
import java.util.SplittableRandom;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Formigas locais de um nó (Ant System). Cada formiga constrói uma rota escolhendo a próxima cidade
 * com probabilidade proporcional a τ^α · η^β, onde η = 1/distância.
 *
 * <p>Para uma formiga na cidade i, a chance de ir para a cidade j ainda não visitada é
 * <pre>
 *   p(i, j) = τ[i][j]^α · η[i][j]^β / Σ_k (τ[i][k]^α · η[i][k]^β)
 * </pre>
 * α pesa a "memória coletiva" (feromônio) e β pesa a gula (cidades próximas). Com α = 0 vira
 * uma busca gulosa aleatorizada; com β = 0 ignora as distâncias.
 *
 * <p>Thread-safety: imutável depois de construída. {@link #runAnts} roda as formigas em paralelo
 * (parallel stream); cada formiga tem seu próprio gerador e só lê a matriz {@code tau} recebida,
 * que deve ser um snapshot que ninguém mais altera.
 */
public final class AntColony {
    private final TspInstance tsp;
    private final double alpha;
    private final double beta;
    private final double[][] etaPow;
    /** null = pool comum (todos os núcleos); senão, pool próprio com {@code ANT_THREADS} threads. */
    private final ForkJoinPool pool;

    public AntColony(TspInstance tsp, double alpha, double beta) {
        this(tsp, alpha, beta, 0);
    }

    /**
     * @param threads threads para as formigas; 0 usa todos os núcleos. Limitar serve para a análise
     *                de desempenho: N nós com T threads cada numa máquina de N·T núcleos imitam
     *                N máquinas de T núcleos, e a linha de base sequencial usa as mesmas T threads.
     */
    public AntColony(TspInstance tsp, double alpha, double beta, int threads) {
        if (threads < 0) throw new IllegalArgumentException("threads < 0: " + threads);
        this.pool = threads == 0 ? null : new ForkJoinPool(threads);
        this.tsp = tsp;
        this.alpha = alpha;
        this.beta = beta;
        int n = tsp.size();
        // η^β não muda durante a execução: pré-calcular evita Math.pow no laço mais quente.
        this.etaPow = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                etaPow[i][j] = Math.pow(1.0 / Math.max(tsp.distance(i, j), 1e-9), beta);
            }
        }
    }

    /**
     * Executa {@code ants} formigas em paralelo (núcleos da máquina) e devolve a melhor rota.
     *
     * @param tau  snapshot da matriz de feromônio (somente leitura)
     * @param ants número de formigas
     * @param seed semente; a mesma semente reproduz exatamente as mesmas rotas
     */
    public Tour runAnts(double[][] tau, int ants, long seed) {
        if (pool != null) return pool.submit(() -> runAntsHere(tau, ants, seed)).join();
        return runAntsHere(tau, ants, seed);
    }

    /** Parallel stream roda no pool de quem chama: o comum, ou o próprio se chamado de dentro dele. */
    private Tour runAntsHere(double[][] tau, int ants, long seed) {
        return IntStream.range(0, ants)
                .parallel()
                .mapToObj(a -> buildTour(tau, new SplittableRandom(seed * 31 + a)))
                .min(Comparator.comparingDouble(Tour::length))
                .orElseThrow();
    }

    /** Constrói uma rota completa a partir de uma cidade inicial sorteada. */
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

    /**
     * Seleção por roleta: sorteia r em [0, total) e percorre as candidatas subtraindo os pesos;
     * a cidade em que r fica &lt;= 0 é a escolhida. Cidades com peso maior ocupam fatias maiores.
     */
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
