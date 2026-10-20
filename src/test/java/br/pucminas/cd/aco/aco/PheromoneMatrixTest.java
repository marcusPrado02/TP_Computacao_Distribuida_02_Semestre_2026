package br.pucminas.cd.aco.aco;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PheromoneMatrixTest {
    private static final double RHO = 0.1;
    private static final Tour TOUR_A = new Tour(new int[] {0, 1, 2, 3}, 10);
    private static final Tour TOUR_B = new Tour(new int[] {0, 2, 1, 3}, 12);

    @Test
    void flattenEReplaceSaoInversos() {
        PheromoneMatrix a = new PheromoneMatrix(4, 1.0);
        a.evaporateAndDeposit(TOUR_A, RHO);
        PheromoneMatrix b = new PheromoneMatrix(4, 1.0);
        b.replace(a.flatten());
        assertArrayEquals(a.flatten(), b.flatten());
    }

    @Test
    void replaceRejeitaTamanhoErrado() {
        PheromoneMatrix m = new PheromoneMatrix(4, 1.0);
        assertThrows(IllegalArgumentException.class, () -> m.replace(new double[9]));
    }

    /**
     * Por que a atualização precisa de exclusão mútua: dois nós fazem "ler → atualizar → propagar"
     * ao mesmo tempo sobre a mesma versão da matriz. Cada um sobrescreve o outro (lost update):
     * as réplicas divergem e nenhuma delas contém os dois depósitos.
     */
    @Test
    void semExclusaoMutuaAsReplicasDivergemEPerdemDeposito() {
        PheromoneMatrix n1 = new PheromoneMatrix(4, 1.0);
        PheromoneMatrix n2 = new PheromoneMatrix(4, 1.0);

        // Concorrente: cada nó atualiza a própria réplica e envia ao outro.
        n1.evaporateAndDeposit(TOUR_A, RHO);
        n2.evaporateAndDeposit(TOUR_B, RHO);
        double[] fromN1 = n1.flatten();
        double[] fromN2 = n2.flatten();
        n1.replace(fromN2);
        n2.replace(fromN1);

        assertFalse(Arrays.equals(n1.flatten(), n2.flatten()), "réplicas deveriam divergir");
        double[] serialized = serialized();
        assertFalse(Arrays.equals(n1.flatten(), serialized));
        assertFalse(Arrays.equals(n2.flatten(), serialized));
    }

    /** Com a seção crítica, o segundo nó atualiza a matriz que já contém o depósito do primeiro. */
    @Test
    void comExclusaoMutuaAsReplicasConvergem() {
        PheromoneMatrix n1 = new PheromoneMatrix(4, 1.0);
        PheromoneMatrix n2 = new PheromoneMatrix(4, 1.0);

        n1.evaporateAndDeposit(TOUR_A, RHO); // N1 na SC
        n2.replace(n1.flatten());            // broadcast síncrono antes de liberar
        n2.evaporateAndDeposit(TOUR_B, RHO); // N2 na SC, sobre a matriz já atualizada
        n1.replace(n2.flatten());

        assertArrayEquals(n1.flatten(), n2.flatten());
        assertTrue(Arrays.equals(n1.flatten(), serialized()));
    }

    private static double[] serialized() {
        PheromoneMatrix m = new PheromoneMatrix(4, 1.0);
        m.evaporateAndDeposit(TOUR_A, RHO);
        m.evaporateAndDeposit(TOUR_B, RHO);
        return m.flatten();
    }
}
