package br.pucminas.cd.aco.mutex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.cd.aco.clock.LamportClock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class RicartAgrawalaTest {

    /** Transporte falso: peers configuráveis, REQUESTs "entregues" e REPLYs registrados. */
    static final class FakeTransport implements MutexTransport {
        final Set<Integer> peers;
        final List<Integer> repliesSent = new CopyOnWriteArrayList<>();

        FakeTransport(Set<Integer> peers) {
            this.peers = peers;
        }

        @Override public Set<Integer> alivePeers() { return peers; }
        @Override public boolean sendRequest(int to, long timestamp) { return true; }
        @Override public void sendReply(int to) { repliesSent.add(to); }
    }

    /** Nó 2 que pediu a seção crítica com timestamp 1 e ainda espera o REPLY do nó 3. */
    private static RicartAgrawala wantingNode(FakeTransport transport) throws InterruptedException {
        RicartAgrawala ra = new RicartAgrawala(2, new LamportClock(), transport);
        Thread.ofVirtual().start(() -> {
            try {
                ra.acquire();
            } catch (InterruptedException ignored) {
            }
        });
        for (int i = 0; i < 100 && ra.state() != RicartAgrawala.State.WANTED; i++) Thread.sleep(10);
        assertEquals(RicartAgrawala.State.WANTED, ra.state());
        return ra;
    }

    @Test
    void releasedRespondeNaHora() {
        FakeTransport t = new FakeTransport(Set.of(1, 3));
        RicartAgrawala ra = new RicartAgrawala(2, new LamportClock(), t);
        ra.onRequest(5, 1);
        assertEquals(List.of(1), t.repliesSent);
    }

    @Test
    void heldAdiaEAoLiberarResponde() throws InterruptedException {
        FakeTransport t = new FakeTransport(Set.of()); // sem peers: acquire retorna na hora
        RicartAgrawala ra = new RicartAgrawala(2, new LamportClock(), t);
        ra.acquire();
        ra.onRequest(1, 1);
        assertTrue(t.repliesSent.isEmpty(), "dentro da seção crítica deve adiar");
        ra.release();
        assertEquals(List.of(1), t.repliesSent);
    }

    @Test
    void wantedComPedidoMaisAntigoRespondeNaHora() throws InterruptedException {
        FakeTransport t = new FakeTransport(Set.of(3));
        RicartAgrawala ra = wantingNode(t); // meu pedido: (ts=1, id=2)
        ra.onRequest(0, 3);                 // (0,3) < (1,2): o outro tem prioridade
        assertEquals(List.of(3), t.repliesSent);
    }

    @Test
    void wantedComPedidoMaisNovoAdia() throws InterruptedException {
        FakeTransport t = new FakeTransport(Set.of(3));
        RicartAgrawala ra = wantingNode(t);
        ra.onRequest(7, 3);                 // (7,3) > (1,2): eu tenho prioridade
        assertTrue(t.repliesSent.isEmpty());
    }

    @Test
    void empateNoTimestampDesempataPeloId() throws InterruptedException {
        FakeTransport t = new FakeTransport(Set.of(3));
        RicartAgrawala ra = wantingNode(t);
        ra.onRequest(1, 1);                 // (1,1) < (1,2): id menor ganha
        ra.onRequest(1, 3);                 // (1,3) > (1,2): adia
        assertEquals(List.of(1), t.repliesSent);
    }
}
