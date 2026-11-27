package br.pucminas.cd.aco.mutex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.cd.aco.clock.LamportClock;
import br.pucminas.cd.aco.rpc.PeerNetwork;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tratamento de falhas no Ricart-Agrawala (Entrega 2): falha isolada de RPC não é permissão.
 *
 * <p>Regressão do bug que o carimbo de versão revelou: na subida, o canal gRPC em reconnect
 * backoff fazia um peer vivo falhar com UNAVAILABLE, isso contava como permissão e dois nós
 * entravam juntos na seção crítica da rodada 1.
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

    /** A janela de suspeita de PeerNetwork: recém-configurado não é suspeito; sem contato, vira. */
    @Test
    void peerNetworkSoSuspeitaDepoisDaJanela() throws Exception {
        try (PeerNetwork net = new PeerNetwork(1, new LamportClock(), Map.of(2, "localhost:1"), "t", 300, 1_000)) {
            assertTrue(!net.isSuspected(2), "peer recém-configurado tem uma janela para responder");
            assertTrue(!net.sendRequest(2, 1), "porta 1: conexão recusada");
            assertTrue(!net.isSuspected(2), "uma falha isolada não torna o peer suspeito");
            Thread.sleep(1_700); // janela = max(1,5 s, 3 × 300 ms)
            assertTrue(net.isSuspected(2));
            net.markAlive(2); // mensagem recebida dele
            assertTrue(!net.isSuspected(2));
        }
    }
}
