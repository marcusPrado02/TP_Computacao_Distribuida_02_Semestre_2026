package br.pucminas.cd.aco.aco;

/**
 * Réplica local da matriz de feromônio global.
 * Escritas globais (evaporateAndDeposit + broadcast) só ocorrem dentro da seção crítica distribuída.
 *
 * <p>Dois níveis de proteção, que resolvem problemas diferentes:
 * <ul>
 *   <li>{@code synchronized} (local): impede que uma thread leia a matriz pela metade enquanto
 *       outra a substitui (handler gRPC de {@code UpdatePheromone} vs. formigas tirando snapshot);</li>
 *   <li>Ricart-Agrawala (distribuído): impede que dois nós façam "ler, atualizar, propagar" ao mesmo
 *       tempo, o que causaria lost update e réplicas divergentes.</li>
 * </ul>
 *
 * <p>A matriz é simétrica (TSP simétrico): todo depósito em [a][b] também vai para [b][a].
 */
public final class PheromoneMatrix {
    /** Piso do feromônio: evita que uma aresta chegue a 0 e nunca mais possa ser escolhida. */
    private static final double TAU_MIN = 1e-9;

    private final int n;
    private final double[][] tau;

    /** Matriz n×n com todas as arestas valendo {@code initial} (τ0). */
    public PheromoneMatrix(int n, double initial) {
        this.n = n;
        this.tau = new double[n][n];
        for (double[] row : tau) java.util.Arrays.fill(row, initial);
    }

    /** Cópia para as formigas lerem sem segurar o lock. */
    public synchronized double[][] snapshot() {
        double[][] copy = new double[n][];
        for (int i = 0; i < n; i++) copy[i] = tau[i].clone();
        return copy;
    }

    /**
     * Evaporação global seguida de depósito de 1/L em cada aresta da rota.
     *
     * <pre>
     *   τ[i][j] = max(τ_min, (1 − ρ) · τ[i][j])      para toda aresta
     *   τ[a][b] = τ[a][b] + 1 / L                   para cada aresta (a, b) da rota
     * </pre>
     * A evaporação esquece aos poucos escolhas antigas; o depósito reforça a rota encontrada,
     * mais forte quanto menor o comprimento L.
     */
    public synchronized void evaporateAndDeposit(Tour tour, double rho) {
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                tau[i][j] = Math.max(TAU_MIN, tau[i][j] * (1 - rho));
            }
        }
        double deposit = 1.0 / tour.length();
        int[] c = tour.cities();
        for (int k = 0; k < c.length; k++) {
            int a = c[k];
            int b = c[(k + 1) % c.length]; // % fecha o ciclo: última cidade volta à primeira
            tau[a][b] += deposit;
            tau[b][a] += deposit;
        }
    }

    /** Matriz em row-major, para envio via gRPC. */
    public synchronized double[] flatten() {
        double[] flat = new double[n * n];
        for (int i = 0; i < n; i++) System.arraycopy(tau[i], 0, flat, i * n, n);
        return flat;
    }

    /** Substitui a réplica local pela matriz recebida de quem estava na seção crítica. */
    public synchronized void replace(double[] flat) {
        if (flat.length != n * n) {
            throw new IllegalArgumentException("Matriz com tamanho " + flat.length + ", esperado " + n * n);
        }
        for (int i = 0; i < n; i++) System.arraycopy(flat, i * n, tau[i], 0, n);
    }
}
