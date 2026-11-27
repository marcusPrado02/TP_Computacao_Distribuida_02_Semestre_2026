package br.pucminas.cd.aco.replica;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.cd.aco.aco.Tour;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckpointTest {
    private static final double FP = 1234.5;

    @TempDir
    Path dir;

    private static ReplicaSnapshot sample() {
        double[] tau = new double[9];
        for (int i = 0; i < tau.length; i++) tau[i] = 0.5 + i;
        return new ReplicaSnapshot(new Version(42, 3), tau, new Tour(new int[] {2, 0, 1}, 99.5), 17);
    }

    @Test
    void gravaELeOMesmoEstado() throws Exception {
        try (Checkpoint ckpt = new Checkpoint(dir, 1, 3, FP, CheckpointTest::sample)) {
            ckpt.save(sample());
            ReplicaSnapshot back = ckpt.load().orElseThrow();
            assertEquals(new Version(42, 3), back.version());
            assertEquals(17, back.lastRound());
            assertArrayEquals(sample().tau(), back.tau(), 0.0);
            assertArrayEquals(new int[] {2, 0, 1}, back.best().cities());
            assertEquals(99.5, back.best().length());
        }
    }

    @Test
    void semArquivoNaoHaEstado() throws Exception {
        try (Checkpoint ckpt = new Checkpoint(dir, 1, 3, FP, CheckpointTest::sample)) {
            assertTrue(ckpt.load().isEmpty());
        }
    }

    @Test
    void checkpointDeOutraInstanciaEhIgnorado() throws Exception {
        try (Checkpoint other = new Checkpoint(dir, 1, 3, FP + 1, CheckpointTest::sample)) {
            other.save(sample());
        }
        try (Checkpoint ckpt = new Checkpoint(dir, 1, 3, FP, CheckpointTest::sample)) {
            assertTrue(ckpt.load().isEmpty());
        }
    }

    /** Arquivo truncado (crash no meio de uma gravação não atômica) não derruba o nó. */
    @Test
    void arquivoTruncadoEhIgnorado() throws Exception {
        try (Checkpoint ckpt = new Checkpoint(dir, 1, 3, FP, CheckpointTest::sample)) {
            ckpt.save(sample());
            byte[] bytes = Files.readAllBytes(ckpt.file());
            Files.write(ckpt.file(), java.util.Arrays.copyOf(bytes, bytes.length / 2));
            assertTrue(ckpt.load().isEmpty());
        }
    }

    @Test
    void markDirtyGravaEmSegundoPlanoOEstadoMaisRecente() throws Exception {
        AtomicReference<ReplicaSnapshot> current = new AtomicReference<>(sample());
        Optional<ReplicaSnapshot> back;
        try (Checkpoint ckpt = new Checkpoint(dir, 2, 3, FP, current::get)) {
            ckpt.markDirty();
            current.set(new ReplicaSnapshot(new Version(43, 1), sample().tau(), null, 18));
            ckpt.markDirty();
            ckpt.close(); // espera a fila
            back = ckpt.load();
        }
        assertEquals(new Version(43, 1), back.orElseThrow().version());
        assertEquals(null, back.get().best());
    }
}
