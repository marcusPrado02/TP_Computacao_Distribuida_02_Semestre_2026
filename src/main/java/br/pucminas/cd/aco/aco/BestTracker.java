package br.pucminas.cd.aco.aco;

/** Melhor rota global conhecida por este nó (propagada junto com o feromônio). */
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

    public synchronized Tour get() {
        return best;
    }
}
