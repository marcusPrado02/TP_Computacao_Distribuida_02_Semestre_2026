package br.pucminas.cd.aco.aco;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AntColonyTest {

    /** Cidades num círculo: a rota ótima é o polígono, conhecida analiticamente. */
    @Test
    void encontraOtimoEmCirculo() {
        int n = 12;
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = 500 + 400 * Math.cos(2 * Math.PI * i / n);
            y[i] = 500 + 400 * Math.sin(2 * Math.PI * i / n);
        }
        TspInstance tsp = TspInstance.fromCoords(x, y);
        int[] polygon = java.util.stream.IntStream.range(0, n).toArray();
        double optimum = tsp.tourLength(polygon);

        double rho = 0.1;
        PheromoneMatrix tau = new PheromoneMatrix(n, 1.0 / (rho * tsp.nearestNeighborLength()));
        AntColony colony = new AntColony(tsp, 1.0, 3.0);
        BestTracker best = new BestTracker();
        for (int it = 0; it < 20; it++) {
            Tour t = colony.runAnts(tau.snapshot(), 20, it);
            tau.evaporateAndDeposit(t, rho);
            best.offer(t);
        }
        assertEquals(optimum, best.get().length(), 1e-6);
    }
}
