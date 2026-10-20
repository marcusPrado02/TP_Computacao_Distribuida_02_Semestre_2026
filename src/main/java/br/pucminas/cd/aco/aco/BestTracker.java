package br.pucminas.cd.aco.aco;

/**
 * Melhor rota global conhecida por este nó (propagada junto com o feromônio).
 *
 * <p>Só aceita rotas estritamente menores, então o valor nunca piora. Thread-safe: os métodos são
 * {@code synchronized} porque a rota é oferecida pelo trabalhador local e por handlers gRPC.
 */
public final class BestTracker {
    private Tour best;

    /** @return true se {@code tour} melhorou o melhor conhecido */
    public synchronized boolean offer(Tour tour) {
        if (tour != null && (best == null || tour.length() < best.length())) {
            best = tour;
            return true;
        }
        return false;
    }

    /** Melhor rota até agora, ou null se nenhuma foi oferecida. */
    public synchronized Tour get() {
        return best;
    }
}
