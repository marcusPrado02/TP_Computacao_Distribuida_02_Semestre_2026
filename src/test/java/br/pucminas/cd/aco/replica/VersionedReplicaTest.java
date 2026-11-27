package br.pucminas.cd.aco.replica;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.cd.aco.aco.PheromoneMatrix;
import br.pucminas.cd.aco.aco.Tour;
import org.junit.jupiter.api.Test;

/** Controle de réplicas da matriz de feromônio: versão, rejeição de escrita velha e conflito. */
class VersionedReplicaTest {
    private static final double RHO = 0.1;
    private static final Tour TOUR_A = new Tour(new int[] {0, 1, 2, 3}, 10);
    private static final Tour TOUR_B = new Tour(new int[] {0, 2, 1, 3}, 12);

    @Test
    void escritaLocalAvancaAVersao() {
        PheromoneMatrix m = new PheromoneMatrix(4, 1.0);
        assertEquals(Version.INITIAL, m.version());
        assertEquals(new Version(1, 7), m.evaporateAndDeposit(TOUR_A, RHO, 7));
        assertEquals(new Version(2, 3), m.evaporateAndDeposit(TOUR_B, RHO, 3));
    }

    /** UPDATE atrasado (deadline estourou, handler executou depois) não volta a réplica no tempo. */
    @Test
    void updateAtrasadoComVersaoAntigaEhRejeitado() {
        PheromoneMatrix writer = new PheromoneMatrix(4, 1.0);
        Version v1 = writer.evaporateAndDeposit(TOUR_A, RHO, 1);
        double[] old = writer.flatten();
        Version v2 = writer.evaporateAndDeposit(TOUR_B, RHO, 2);
        double[] latest = writer.flatten();

        PheromoneMatrix replica = new PheromoneMatrix(4, 1.0);
        assertTrue(replica.applyIfNewer(latest, v2).applied());
        PheromoneMatrix.Applied late = replica.applyIfNewer(old, v1); // chega fora de ordem
        assertFalse(late.applied());
        assertEquals(v2, late.local());
        assertArrayEquals(latest, replica.flatten(), 0.0);
    }

    /** Nó que voltou com a matriz inicial não sobrescreve o progresso de quem já avançou. */
    @Test
    void matrizInicialDeNoReiniciadoNaoApagaProgresso() {
        PheromoneMatrix advanced = new PheromoneMatrix(4, 1.0);
        advanced.evaporateAndDeposit(TOUR_A, RHO, 1);
        double[] before = advanced.flatten();

        PheromoneMatrix restarted = new PheromoneMatrix(4, 1.0);
        assertFalse(advanced.applyIfNewer(restarted.flatten(), restarted.version()).applied());
        assertArrayEquals(before, advanced.flatten(), 0.0);
    }

    /**
     * Exclusão mútua violada (o detector tomou um peer lento por morto): N1 e N2 escrevem sobre a
     * mesma versão e trocam as matrizes. Sem versão, as réplicas divergiam (ver PheromoneMatrixTest).
     * Com o carimbo, as duas detectam o conflito e convergem para a escrita de maior carimbo.
     */
    @Test
    void escritasConcorrentesSaoDetectadasEAsReplicasConvergem() {
        PheromoneMatrix n1 = new PheromoneMatrix(4, 1.0);
        PheromoneMatrix n2 = new PheromoneMatrix(4, 1.0);

        Version w1 = n1.evaporateAndDeposit(TOUR_A, RHO, 1);
        Version w2 = n2.evaporateAndDeposit(TOUR_B, RHO, 2);
        double[] fromN1 = n1.flatten();
        double[] fromN2 = n2.flatten();

        PheromoneMatrix.Applied atN1 = n1.applyIfNewer(fromN2, w2);
        PheromoneMatrix.Applied atN2 = n2.applyIfNewer(fromN1, w1);

        assertTrue(atN1.conflict() && atN2.conflict(), "as duas réplicas devem detectar o conflito");
        assertTrue(atN1.applied(), "N1 adota a escrita de N2 (maior carimbo)");
        assertFalse(atN2.applied(), "N2 mantém a própria escrita");
        assertArrayEquals(n1.flatten(), n2.flatten(), 0.0);
        assertEquals(n1.version(), n2.version());
        assertEquals(1, n1.conflicts());
    }

    @Test
    void ordemDosCarimbosEhTotal() {
        assertTrue(new Version(3, 1).isNewerThan(new Version(2, 9)));
        assertTrue(new Version(3, 2).isNewerThan(new Version(3, 1)));
        assertFalse(new Version(3, 1).isNewerThan(new Version(3, 1)));
        assertTrue(new Version(3, 1).conflictsWith(new Version(3, 2)));
        assertFalse(new Version(3, 1).conflictsWith(new Version(4, 2)));
    }
}
