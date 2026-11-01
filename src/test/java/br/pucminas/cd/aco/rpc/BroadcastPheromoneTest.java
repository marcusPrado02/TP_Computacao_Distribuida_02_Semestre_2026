package br.pucminas.cd.aco.rpc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.cd.aco.aco.Tour;
import br.pucminas.cd.aco.clock.LamportClock;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * O broadcast de feromônio roda dentro da seção crítica: um peer já dado como morto não pode
 * segurar a SC pelo BROADCAST_TIMEOUT_MS inteiro quando a quarentena dele expira.
 *
 * <p>O mesmo vale para o coordenador da rodada quando ele morre no meio dela.
 *
 * <p>Regressão do ensaio em {@code docker-compose.lan.yml} (timeout de broadcast 5 s): depois de
 * parar o líder, cada sobrevivente ficava ~5 s na SC tentando o morto, e a primeira rodada do novo
 * líder levava ~15 s em vez de ~1 s.
 */
class BroadcastPheromoneTest {

    @Test
    void peerMortoNaoSeguraOBroadcastQuandoAQuarentenaExpira() throws Exception {
        long broadcastTimeout = 1_500;
        // "Buraco negro": aceita a conexão TCP e nunca responde, como um host que parou de
        // processar. O RPC só termina no deadline (não falha rápido com connection refused).
        try (ServerSocket blackHole = new ServerSocket(0)) {
            List<Socket> accepted = new CopyOnWriteArrayList<>();
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) accepted.add(blackHole.accept());
                } catch (Exception ignored) {
                    // socket fechado no fim do teste
                }
            });
            try (PeerNetwork net = new PeerNetwork(1, new LamportClock(),
                    Map.of(2, "localhost:" + blackHole.getLocalPort()), "t", 300, broadcastTimeout)) {
                double[] tau = new double[9];
                Tour best = new Tour(new int[] {0, 1, 2}, 3);

                // 1º broadcast: N2 ainda consta como vivo, então o líder paga o timeout e o marca morto.
                net.broadcastPheromone(1, tau, best, 1);
                assertFalse(net.alivePeers().contains(2), "N2 deveria estar marcado como inacessível");

                Thread.sleep(2_200); // passa a quarentena (2 s)

                long start = System.nanoTime();
                net.broadcastPheromone(2, tau, best, 1);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                assertTrue(elapsedMs < broadcastTimeout / 3,
                        "broadcast esperou o peer morto por " + elapsedMs + " ms");

                // Líder que cai no meio da rodada: N2 é o coordenador e está morto. Ainda recebe o
                // envio (em segundo plano), mas a SC não pode esperar por ele.
                start = System.nanoTime();
                net.broadcastPheromone(3, tau, best, 2);
                elapsedMs = (System.nanoTime() - start) / 1_000_000;
                assertTrue(elapsedMs < broadcastTimeout / 3,
                        "broadcast esperou o coordenador morto por " + elapsedMs + " ms");
            } finally {
                blackHole.close();
                acceptor.join(1_000);
                for (Socket s : accepted) s.close();
            }
        }
    }
}
