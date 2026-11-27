package br.pucminas.cd.aco.aco;

import br.pucminas.cd.aco.replica.Version;

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
 *
 * <p><b>Controle de réplicas (Entrega 2).</b> Cada réplica guarda o {@link Version carimbo} da última
 * escrita aplicada. Uma escrita local gera {@code versão + 1}; uma escrita remota só é aplicada se
 * o carimbo dela for <i>maior</i> ({@link #applyIfNewer}). Isso resolve três falhas do protocolo
 * anterior, em que a matriz era trocada às cegas:
 * <ul>
 *   <li>UPDATE atrasado (deadline estourou, mas o handler rodou depois) não sobrescreve matriz nova;</li>
 *   <li>nó que voltou com a matriz inicial (versão 0) não apaga o progresso do cluster;</li>
 *   <li>se a exclusão mútua falhar (peer lento tomado por morto) e duas escritas partirem da mesma
 *       versão, o conflito é <b>detectado</b> e todas as réplicas convergem para a de maior carimbo
 *       (uma das duas contribuições se perde, mas ninguém diverge).</li>
 * </ul>
 */
public final class PheromoneMatrix {
    /** Piso do feromônio: evita que uma aresta chegue a 0 e nunca mais possa ser escolhida. */
    private static final double TAU_MIN = 1e-9;

    private final int n;
    private final double[][] tau;
    private Version version = Version.INITIAL;
    private long conflicts;

    /** Resultado de {@link #applyIfNewer}: se aplicou e se o carimbo revelou escrita concorrente. */
    public record Applied(boolean applied, boolean conflict, Version local) {}

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
        evaporateAndDeposit(tour, rho, 0);
    }

    /**
     * Escrita local (dentro da seção crítica): atualiza e avança a versão.
     *
     * @return carimbo da nova versão, a ser propagado junto com a matriz
     */
    public synchronized Version evaporateAndDeposit(Tour tour, double rho, int writer) {
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
        version = version.next(writer);
        return version;
    }

    /** Matriz em row-major, para envio via gRPC. */
    public synchronized double[] flatten() {
        double[] flat = new double[n * n];
        for (int i = 0; i < n; i++) System.arraycopy(tau[i], 0, flat, i * n, n);
        return flat;
    }

    /** Substitui a réplica local sem olhar versão (só para testes do comportamento sem controle). */
    public synchronized void replace(double[] flat) {
        copyIn(flat);
    }

    /**
     * Aplica a matriz recebida só se o carimbo for mais novo que o local (last-writer-wins).
     * Mesma versão com outro escritor conta como conflito mesmo quando aplica: as duas escritas
     * partiram da mesma base, então uma contribuição se perdeu.
     */
    public synchronized Applied applyIfNewer(double[] flat, Version incoming) {
        boolean conflict = incoming.conflictsWith(version);
        if (conflict) conflicts++;
        if (!incoming.isNewerThan(version)) return new Applied(false, conflict, version);
        copyIn(flat);
        version = incoming;
        return new Applied(true, conflict, version);
    }

    /** Carimbo da última escrita aplicada. */
    public synchronized Version version() {
        return version;
    }

    /** Quantos conflitos (escritas concorrentes) esta réplica já detectou. */
    public synchronized long conflicts() {
        return conflicts;
    }

    /** Matriz e carimbo lidos juntos (atômico): para FetchState e checkpoint. */
    public synchronized Versioned versioned() {
        return new Versioned(flatten(), version);
    }

    /** Par matriz + carimbo de um mesmo instante. */
    public record Versioned(double[] tau, Version version) {}

    private void copyIn(double[] flat) {
        if (flat.length != n * n) {
            throw new IllegalArgumentException("Matriz com tamanho " + flat.length + ", esperado " + n * n);
        }
        for (int i = 0; i < n; i++) System.arraycopy(flat, i * n, tau[i], 0, n);
    }
}
