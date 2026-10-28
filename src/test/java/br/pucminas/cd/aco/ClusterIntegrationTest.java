package br.pucminas.cd.aco;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.cd.aco.config.NodeConfig;
import br.pucminas.cd.aco.grpc.Header;
import br.pucminas.cd.aco.grpc.HeartbeatMsg;
import br.pucminas.cd.aco.grpc.NodeServiceGrpc;
import br.pucminas.cd.aco.rpc.ClusterAuth;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Cluster de 3 nós na mesma JVM, falando gRPC de verdade por portas locais.
 *
 * <p>Os logs saem todos com o mesmo [N..] porque {@code Log} é global ao processo
 * (em produção há um nó por processo).
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class ClusterIntegrationTest {
    private static final String TOKEN = "token-de-teste";
    private static final int SIZE = 3;

    private final List<Node> nodes = new ArrayList<>();
    private int[] ports;

    @AfterEach
    void stopCluster() throws InterruptedException {
        for (Node n : nodes) n.close();
    }

    @Test
    void elegeMaiorIdExecutaTodasAsRodadasEReplicasConvergem() throws Exception {
        startCluster(3);

        await(() -> nodes.stream().allMatch(n -> n.leaderId() == 3), 15);
        Node leader = nodes.get(2);
        await(leader::finishedAsLeader, 60);

        double[] reference = leader.pheromone();
        for (Node n : nodes) {
            assertEquals(3, n.lastRound(), "N" + n.id() + " não viu todas as rodadas");
            assertNotNull(n.best());
            // Toda saída da seção crítica propaga a matriz antes de liberar: réplicas idênticas.
            assertArrayEquals(reference, n.pheromone(), "réplica de N" + n.id() + " divergiu");
        }
    }

    @Test
    void quedaDoLiderElegeOProximoMaiorId() throws Exception {
        startCluster(1_000);
        await(() -> nodes.stream().allMatch(n -> n.leaderId() == 3), 15);

        nodes.remove(2).close(); // derruba o líder N3

        // 5 heartbeats perdidos (~5 s) e nova eleição
        await(() -> nodes.stream().allMatch(n -> n.leaderId() == 2), 20);
    }

    /**
     * Seguidor morto não é o líder: ninguém manda heartbeat nele, então sem probe ele só seria
     * notado no próximo broadcast (líder) ou nunca (nos pedidos de seção crítica dos outros).
     * Antes da correção, o líder esperava 30 s por rodada e os seguidores podiam travar.
     */
    @Test
    void quedaDeUmSeguidorNaoTravaNemAtrasaAsRodadas() throws Exception {
        startCluster(30);
        await(() -> nodes.stream().allMatch(n -> n.leaderId() == 3), 15);
        await(() -> nodes.stream().allMatch(n -> n.lastRound() >= 2), 20);

        nodes.remove(0).close(); // N1 é seguidor; N3 continua líder
        Node leader = nodes.get(1);

        await(leader::finishedAsLeader, 30);
        for (Node n : nodes) {
            assertEquals(30, n.lastRound(), "N" + n.id() + " não concluiu as rodadas");
        }
        double[] reference = leader.pheromone();
        for (Node n : nodes) {
            assertArrayEquals(reference, n.pheromone(), "réplica de N" + n.id() + " divergiu");
        }
    }

    @Test
    void chamadaSemTokenOuDeRemetenteDesconhecidoEhRecusada() throws Exception {
        startCluster(1);
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", ports[0]).usePlaintext().build();
        try {
            HeartbeatMsg fromUnknown = HeartbeatMsg.newBuilder()
                    .setHeader(Header.newBuilder().setSenderId(99).setLamport(1))
                    .build();

            var noToken = NodeServiceGrpc.newBlockingStub(channel).withDeadlineAfter(2, TimeUnit.SECONDS);
            assertStatus(Status.Code.UNAUTHENTICATED, () -> noToken.heartbeat(fromUnknown));

            var withToken = NodeServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(ClusterAuth.clientInterceptor(TOKEN))
                    .withDeadlineAfter(2, TimeUnit.SECONDS);
            assertStatus(Status.Code.PERMISSION_DENIED, () -> withToken.heartbeat(fromUnknown));
        } finally {
            channel.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ helpers

    private void startCluster(int rounds) throws IOException {
        ports = new int[SIZE];
        for (int i = 0; i < SIZE; i++) ports[i] = freePort();
        for (int id = 1; id <= SIZE; id++) {
            Map<Integer, String> peers = new LinkedHashMap<>();
            for (int other = 1; other <= SIZE; other++) {
                if (other != id) peers.put(other, "localhost:" + ports[other - 1]);
            }
            NodeConfig cfg = new NodeConfig(id, ports[id - 1], peers, TOKEN, null,
                    12, 7, rounds, 5, 1.0, 3.0, 0.1, 500,
                    new NodeConfig.Timeouts(500, 2_000, 3_000, 0));
            nodes.add(new Node(cfg));
        }
        for (Node n : nodes) n.start();
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

    private static void assertStatus(Status.Code expected, Runnable call) {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class, call::run);
        assertEquals(expected, e.getStatus().getCode());
    }
}
