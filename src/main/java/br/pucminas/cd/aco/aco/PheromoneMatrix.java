package br.pucminas.cd.aco.aco;

/**
 * Réplica local da matriz de feromônio global.
 * Escritas globais (evaporateAndDeposit + broadcast) só ocorrem dentro da seção crítica distribuída.
 */
public final class PheromoneMatrix {
    private static final double TAU_MIN = 1e-9;

    private final int n;
    private final double[][] tau;

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

    /** Evaporação global seguida de depósito de 1/L em cada aresta da rota. */
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
            int b = c[(k + 1) % c.length];
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
