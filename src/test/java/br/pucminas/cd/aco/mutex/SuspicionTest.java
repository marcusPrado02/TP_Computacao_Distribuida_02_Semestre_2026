package br.pucminas.cd.aco.mutex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.cd.aco.clock.LamportClock;
import br.pucminas.cd.aco.grpc.Ack;
import br.pucminas.cd.aco.grpc.CsRequest;
import br.pucminas.cd.aco.grpc.Header;
import br.pucminas.cd.aco.grpc.HeartbeatMsg;
import br.pucminas.cd.aco.grpc.NodeServiceGrpc;
import br.pucminas.cd.aco.rpc.PeerNetwork;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;

/**
 * Tratamento de falhas no Ricart-Agrawala (Entrega 2): falha isolada de RPC não é permissão.
 *
 * <p>Regressão do bug que o carimbo de versão revelou: na subida, o canal gRPC em reconnect
 * backoff fazia um peer vivo falhar com UNAVAILABLE, isso contava como permissão e dois nós
 * entravam juntos na seção crítica da rodada 1.
 *
 * <p>Silêncio também não é permissão: dois seguidores só se falam na fase da seção crítica, e numa
 * rodada longa passam a janela de suspeita inteira sem contato, os dois vivos.
 */
class SuspicionTest {

    /** Peer 3 vivo, mas os primeiros REQUESTs falham (canal em backoff); ainda não é suspeito. */
    static final class FlakyTransport implements MutexTransport {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicBoolean suspected = new AtomicBoolean(false);

        @Override public Set<Integer> allPeers() { return Set.of(3); }
        @Override public Set<Integer> alivePeers() { return Set.of(); } // falhou: fora dos "vivos"
        @Override public boolean sendRequest(int to, long timestamp) {
            attempts.incrementAndGet();
            return false;
        }
        @Override public void sendReply(int to, long requestTs) {}
        @Override public boolean isSuspected(int peer) { return suspected.get(); }
    }

    @Test
    void falhaDePeerNaoSuspeitoNaoEhPermissao() throws Exception {
        FlakyTransport t = new FlakyTransport();
        RicartAgrawala ra = new RicartAgrawala(2, new LamportClock(), t);
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                ra.acquire();
            } catch (InterruptedException ignored) {
            }
        });
        try {
            Thread.sleep(1_300); // vários reenvios a cada 500 ms, todos falhando
            assertEquals(RicartAgrawala.State.WANTED, ra.state(),
                    "entrou na SC sem o REPLY de um peer que não é suspeito");
            assertTrue(t.attempts.get() >= 2, "o pedido deveria ser reenviado");

            // O peer 3 responde de verdade: agora entra.
            ra.onReply(3, 1);
            waiter.join(2_000);
            assertEquals(RicartAgrawala.State.HELD, ra.state());
        } finally {
            waiter.interrupt();
            ra.close();
        }
    }

    @Test
    void peerSuspeitoDeixaDeSerEsperado() throws Exception {
        FlakyTransport t = new FlakyTransport();
        t.suspected.set(true); // sem contato há a janela inteira: dado como morto
        RicartAgrawala ra = new RicartAgrawala(2, new LamportClock(), t);
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                ra.acquire();
            } catch (InterruptedException ignored) {
            }
        });
        waiter.join(2_000);
        assertEquals(RicartAgrawala.State.HELD, ra.state());
        ra.close();
    }

    /**
     * A janela de suspeita de PeerNetwork é medida entre falhas: nem uma falha isolada nem o
     * silêncio depois dela bastam; falhas do início ao fim da janela, sim.
     */
    @Test
    void peerNetworkSoSuspeitaDepoisDaJanela() throws Exception {
        try (PeerNetwork net = new PeerNetwork(1, new LamportClock(), Map.of(2, "localhost:1"), "t", 300, 1_000)) {
            assertTrue(!net.isSuspected(2), "peer recém-configurado não é suspeito");
            assertTrue(!net.sendRequest(2, 1), "porta 1: conexão recusada");
            assertTrue(!net.isSuspected(2), "uma falha isolada não torna o peer suspeito");
            Thread.sleep(1_700); // janela = max(1,5 s, 3 × 300 ms)
            assertTrue(!net.isSuspected(2), "silêncio depois de uma falha também não");
            assertTrue(!net.sendRequest(2, 2), "continua recusando");
            assertTrue(net.isSuspected(2), "falhou no início e no fim da janela");
            net.markAlive(2); // mensagem recebida dele
            assertTrue(!net.isSuspected(2));
        }
    }

    /** Peer morto de verdade custa uma janela e depois deixa de ser esperado (não trava a SC). */
    @Test
    void peerMortoCustaUmaJanelaEDeixaDeSerEsperado() throws Exception {
        try (PeerNetwork net = new PeerNetwork(1, new LamportClock(), Map.of(2, "localhost:1"), "t", 300, 1_000)) {
            RicartAgrawala ra = new RicartAgrawala(1, new LamportClock(), net);
            long start = System.nanoTime();
            Thread waiter = Thread.ofVirtual().start(() -> {
                try {
                    ra.acquire();
                } catch (InterruptedException ignored) {
                }
            });
            waiter.join(5_000);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals(RicartAgrawala.State.HELD, ra.state(), "peer morto travou a seção crítica");
            assertTrue(elapsedMs >= 1_400, "entrou em " + elapsedMs + " ms, sem esperar a janela");
            ra.close();
        }
    }

    // ---------------------------------------------------------------- peers vivos, gRPC real

    /** Peer de verdade (servidor gRPC) que só atende Heartbeat e RequestCS. */
    static final class PeerStub extends NodeServiceGrpc.NodeServiceImplBase {
        final int id;
        final long ackDelayMs;
        final AtomicInteger failuresLeft;
        volatile LongConsumer onRequest = ts -> {};

        PeerStub(int id, long ackDelayMs, int failures) {
            this.id = id;
            this.ackDelayMs = ackDelayMs;
            this.failuresLeft = new AtomicInteger(failures);
        }

        private Ack ack(long lamport) {
            return Ack.newBuilder()
                    .setHeader(Header.newBuilder().setSenderId(id).setLamport(lamport + 1))
                    .build();
        }

        @Override
        public void heartbeat(HeartbeatMsg req, StreamObserver<Ack> obs) {
            obs.onNext(ack(req.getHeader().getLamport()));
            obs.onCompleted();
        }

        @Override
        public void requestCS(CsRequest req, StreamObserver<Ack> obs) {
            if (failuresLeft.getAndDecrement() > 0) { // falha transitória de um RPC, com o peer vivo
                obs.onError(Status.UNAVAILABLE.withDescription("falha transitória").asRuntimeException());
                return;
            }
            onRequest.accept(req.getRequestTs());
            try {
                Thread.sleep(ackDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            obs.onNext(ack(req.getHeader().getLamport()));
            obs.onCompleted();
        }
    }

    /**
     * N1 (PeerNetwork e RicartAgrawala reais) pede a SC depois de {@code silenceMs} sem falar com
     * ninguém. N2 está fora da SC e responde na hora. N3 está <b>dentro</b> da SC: confirma o
     * REQUEST (depois de {@code n3AckDelayMs}, recusando os {@code n3Failures} primeiros) e só manda
     * o REPLY quando o teste "libera". Os dois estão vivos o tempo todo.
     */
    private void assertWaitsForPeerInsideCs(long silenceMs, long n3AckDelayMs, int n3Failures) throws Exception {
        PeerStub n2 = new PeerStub(2, 0, 0);
        PeerStub n3 = new PeerStub(3, n3AckDelayMs, n3Failures);
        Server s2 = ServerBuilder.forPort(0).addService(n2).build().start();
        Server s3 = ServerBuilder.forPort(0).addService(n3).build().start();
        LamportClock clock = new LamportClock();
        PeerNetwork net = new PeerNetwork(1, clock,
                Map.of(2, "localhost:" + s2.getPort(), 3, "localhost:" + s3.getPort()), "t", 500, 2_000);
        RicartAgrawala ra = new RicartAgrawala(1, clock, net);
        // O que NodeServiceImpl faz no N1 ao receber um REPLY: receive() marca o remetente como
        // vivo e entrega ao mutex.
        n2.onRequest = ts -> {
            net.markAlive(2);
            ra.onReply(2, ts);
        };
        AtomicLong n3RequestTs = new AtomicLong(-1);
        n3.onRequest = n3RequestTs::set;
        Thread waiter = null;
        try {
            assertTrue(net.heartbeat(2) && net.heartbeat(3), "peers deveriam estar no ar");
            Thread.sleep(silenceMs); // fase das formigas: seguidores não se falam
            waiter = Thread.ofVirtual().start(() -> {
                try {
                    ra.acquire();
                } catch (InterruptedException ignored) {
                }
            });
            Thread.sleep(300); // o REPLY de N2 já chegou; o de N3, não
            assertEquals(RicartAgrawala.State.WANTED, ra.state(), "entrou na SC com N3 dentro dela");

            // N3 sai da SC e responde: agora entra.
            for (int i = 0; i < 40 && n3RequestTs.get() < 0; i++) Thread.sleep(50);
            net.markAlive(3);
            ra.onReply(3, n3RequestTs.get());
            waiter.join(2_000);
            assertEquals(RicartAgrawala.State.HELD, ra.state());
        } finally {
            if (waiter != null) waiter.interrupt();
            ra.close();
            net.close();
            s2.shutdownNow();
            s3.shutdownNow();
        }
    }

    /**
     * Nenhum RPC falha: o REPLY de N2 só chega antes do ack do REQUEST a N3 (150 ms; o deadline é
     * 500 ms). Com a janela contada desde o último contato, N3 saía da espera nesse despertar.
     */
    @Test
    void peerVivoEmSilencioNaoSaiDaEsperaSemResponder() throws Exception {
        assertWaitsForPeerInsideCs(1_700, 150, 0);
    }

    /** O caso da regressão acima (uma falha de RPC com o peer vivo), só que depois do silêncio. */
    @Test
    void falhaIsoladaDepoisDoSilencioNaoEhPermissao() throws Exception {
        assertWaitsForPeerInsideCs(1_700, 0, 1);
    }
}
