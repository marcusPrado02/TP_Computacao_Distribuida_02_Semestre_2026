package br.pucminas.cd.aco;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.cd.aco.config.NodeConfig;
import br.pucminas.cd.aco.replica.Version;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tratamento de falhas da Entrega 2, com 3 nós e gRPC real na mesma JVM:
 * nó que volta recupera o estado, líder que cai no meio é substituído sem perder rodada, e o
 * cluster inteiro reinicia do checkpoint.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class FaultToleranceIntegrationTest {
    private static final String TOKEN = "token-de-teste";
    private static final int SIZE = 3;

    /** id -&gt; nó vivo (TreeMap: fecha em ordem). */
    private final Map<Integer, Node> nodes = new TreeMap<>();
    private final Map<Integer, NodeConfig> configs = new TreeMap<>();

    @TempDir
    Path stateDir;

    @AfterEach
    void stopCluster() throws InterruptedException {
        for (Node n : nodes.values()) n.close();
        nodes.clear();
    }

    /**
     * Seguidor cai e volta: antes, voltava com a matriz inicial (versão 0, rodada 0) e, ao escrever,
     * apagava o progresso dos outros. Agora sincroniza na subida (FetchState) e já entra atualizado.
     */
    @Test
    void noQueVoltaRecuperaOEstadoDoCluster() throws Exception {
        configure(400, 20, 10, 40, null);
        startAll();
        await(() -> leaderIs(3), 15);
        await(() -> nodes.get(1).lastRound() >= 5, 30);

        stop(1);
        await(() -> nodes.get(3).lastRound() >= 15, 30);
        Version clusterBefore = nodes.get(3).version();

        Node back = start(1);
        // start() é síncrono na recuperação: ao voltar, a réplica já não é a inicial.
        assertTrue(!back.version().isNewerThan(nodes.get(3).version()), "versão inventada");
        assertTrue(back.version().number() >= clusterBefore.number(),
                "N1 voltou com " + back.version() + ", cluster estava em " + clusterBefore);
        assertTrue(back.lastRound() >= 15, "N1 voltou sem saber a rodada: " + back.lastRound());

        await(() -> nodes.get(3).finishedAsLeader(), 90);
        assertReplicasIdentical();
    }

    /**
     * Líder cai no meio da execução. O novo líder sincroniza, retoma a rodada interrompida (antes
     * era pulada) e termina todas; as réplicas sobreviventes ficam idênticas.
     */
    @Test
    void novoLiderRetomaAsRodadasEConclui() throws Exception {
        int rounds = 300;
        configure(rounds, 20, 10, 40, null);
        startAll();
        await(() -> leaderIs(3), 15);
        await(() -> nodes.get(3).lastRound() >= 10, 30);
        Version beforeCrash = nodes.get(2).version();
        assertTrue(nodes.get(3).lastRound() < rounds, "rodadas acabaram antes da queda; teste sem efeito");

        stop(3);
        await(() -> leaderIs(2), 20);
        await(() -> nodes.get(2).finishedAsLeader(), 90);

        for (Node n : nodes.values()) {
            assertEquals(rounds, n.lastRound(), "N" + n.id() + " não concluiu");
            assertTrue(n.version().number() > beforeCrash.number(), "versão regrediu em N" + n.id());
        }
        assertReplicasIdentical();
    }

    /**
     * Cluster inteiro cai (falta de energia, por exemplo). Com STATE_DIR, cada nó volta do próprio
     * checkpoint e o líder continua de onde parou, em vez de recomeçar da rodada 1.
     */
    @Test
    void clusterInteiroReiniciaDoCheckpoint() throws Exception {
        configure(8, 12, 5, 1, stateDir.toString());
        startAll();
        await(() -> leaderIs(3), 15);
        await(() -> nodes.get(3).finishedAsLeader(), 60);
        Version saved = nodes.get(3).version();
        assertTrue(saved.number() > 0);
        stopCluster(); // close() espera a gravação pendente do checkpoint

        // Mesma instância, mais rodadas: precisa continuar da 8.
        configure(12, 12, 5, 1, stateDir.toString());
        for (int id = 1; id <= SIZE; id++) {
            Node n = new Node(configs.get(id));
            nodes.put(id, n);
            n.start();
            assertTrue(n.lastRound() >= 8, "N" + id + " voltou sem o checkpoint: rodada " + n.lastRound());
        }
        assertEquals(saved, nodes.get(3).version(), "N3 deveria voltar exatamente da versão gravada");

        await(() -> leaderIs(3), 15);
        await(() -> nodes.get(3).finishedAsLeader(), 60);
        for (Node n : nodes.values()) assertEquals(12, n.lastRound());
        assertTrue(nodes.get(3).version().isNewerThan(saved));
        assertReplicasIdentical();
    }

    // ------------------------------------------------------------------ helpers

    private int[] ports;

    /** Mesmas portas em reconfigurações: o nó que volta precisa ser achado no mesmo endereço. */
    private void configure(int rounds, int cities, int ants, int iterations, String dir) throws IOException {
        if (ports == null) {
            ports = new int[SIZE];
            for (int i = 0; i < SIZE; i++) ports[i] = freePort();
        }
        for (int id = 1; id <= SIZE; id++) {
            Map<Integer, String> peers = new LinkedHashMap<>();
            for (int other = 1; other <= SIZE; other++) {
                if (other != id) peers.put(other, "localhost:" + ports[other - 1]);
            }
            NodeConfig cfg = new NodeConfig(id, ports[id - 1], peers, TOKEN, null,
                    cities, 7, rounds, ants, iterations, 1.0, 3.0, 0.1, 500,
                    new NodeConfig.Timeouts(500, 2_000, 3_000, 0));
            configs.put(id, cfg.withStateDir(dir));
        }
    }

    private void startAll() throws IOException {
        for (int id = 1; id <= SIZE; id++) nodes.put(id, new Node(configs.get(id)));
        for (Node n : nodes.values()) n.start();
    }

    private Node start(int id) throws IOException {
        Node n = new Node(configs.get(id));
        nodes.put(id, n);
        n.start();
        return n;
    }

    private void stop(int id) throws InterruptedException {
        nodes.remove(id).close();
    }

    private boolean leaderIs(int id) {
        return nodes.values().stream().allMatch(n -> n.leaderId() == id);
    }

    private void assertReplicasIdentical() {
        Node ref = nodes.values().iterator().next();
        // O último UPDATE pode estar chegando: espera um pouco antes de comparar.
        for (int i = 0; i < 40 && !allEqual(ref); i++) sleep(50);
        for (Node n : nodes.values()) {
            assertEquals(ref.version(), n.version(), "versão de N" + n.id() + " divergiu");
            assertArrayEquals(ref.pheromone(), n.pheromone(), "réplica de N" + n.id() + " divergiu");
        }
    }

    private boolean allEqual(Node ref) {
        return nodes.values().stream().allMatch(n -> n.version().equals(ref.version()));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void await(BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(condition.getAsBoolean(), "condição não satisfeita em " + seconds + " s");
    }
}
