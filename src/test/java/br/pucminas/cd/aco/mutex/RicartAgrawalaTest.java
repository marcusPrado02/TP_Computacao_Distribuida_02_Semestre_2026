package br.pucminas.cd.aco.mutex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.cd.aco.clock.LamportClock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class RicartAgrawalaTest {

    /** Transporte falso: peers configuráveis (mutáveis), REQUESTs "entregues" e REPLYs registrados. */
    static final class FakeTransport implements MutexTransport {
        final Set<Integer> peers = ConcurrentHashMap.newKeySet();
        final List<Integer> repliesSent = new CopyOnWriteArrayList<>();

        FakeTransport(Set<Integer> peers) {
            this.peers.addAll(peers);
        }

        @Override public Set<Integer> allPeers() { return Set.copyOf(peers); }
        @Override public Set<Integer> alivePeers() { return Set.copyOf(peers); }
        @Override public boolean sendRequest(int to, long timestamp) { return true; }
        @Override public void sendReply(int to, long requestTs) { repliesSent.add(to); }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 300 && !condition.getAsBoolean(); i++) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condição não satisfeita a tempo");
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
        await(() -> ra.state() == RicartAgrawala.State.WANTED);
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

    @Test
    void replyDePedidoAntigoNaoLiberaPedidoAtual() throws InterruptedException {
        FakeTransport t = new FakeTransport(Set.of(3));
        RicartAgrawala ra = wantingNode(t); // pedido atual: ts=1
        ra.onReply(3, 0);                   // REPLY atrasado de um pedido anterior
        Thread.sleep(50);
        assertEquals(RicartAgrawala.State.WANTED, ra.state());
        ra.onReply(3, 1);
        await(() -> ra.state() == RicartAgrawala.State.HELD);
    }

    @Test
    void peerQueCaiDuranteEsperaDeixaDeSerAguardado() throws InterruptedException {
        FakeTransport t = new FakeTransport(Set.of(3));
        RicartAgrawala ra = wantingNode(t);
        t.peers.remove(3);                  // detector de falhas marcou N3 como inacessível
        await(() -> ra.state() == RicartAgrawala.State.HELD);
    }

    @Test
    void releaseForaDaSecaoCriticaEhErro() {
        RicartAgrawala ra = new RicartAgrawala(2, new LamportClock(), new FakeTransport(Set.of()));
        assertThrows(IllegalStateException.class, ra::release);
    }

    @Test
    void pedidoReenviadoRecuperaReplyPerdido() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        RicartAgrawala[] ra = new RicartAgrawala[1];
        MutexTransport t = new MutexTransport() {
            @Override public Set<Integer> allPeers() { return Set.of(3); }
            @Override public Set<Integer> alivePeers() { return Set.of(3); }
            @Override public boolean sendRequest(int to, long ts) {
                // O primeiro REQUEST foi entregue, mas o REPLY se perdeu; no reenvio, responde.
                if (calls.incrementAndGet() >= 2) ra[0].onReply(3, ts);
                return true;
            }
            @Override public void sendReply(int to, long ts) {}
        };
        ra[0] = new RicartAgrawala(1, new LamportClock(), t);
        Thread th = Thread.ofVirtual().start(() -> {
            try { ra[0].acquire(); } catch (InterruptedException ignored) {}
        });
        await(() -> ra[0].state() == RicartAgrawala.State.HELD);
        assertTrue(calls.get() >= 2, "deveria ter reenviado o REQUEST");
        ra[0].release();
        th.join(1000);
        ra[0].close();
    }

    @Test
    void peerQueCaiDepoisDeReceberRequestNaoTrava() throws InterruptedException {
        Set<Integer> alive = ConcurrentHashMap.newKeySet();
        alive.add(3);
        AtomicInteger calls = new AtomicInteger();
        MutexTransport t = new MutexTransport() {
            @Override public Set<Integer> allPeers() { return Set.of(3); }
            @Override public Set<Integer> alivePeers() { return Set.copyOf(alive); }
            @Override public boolean sendRequest(int to, long ts) {
                if (calls.incrementAndGet() == 1) return true; // recebeu o REQUEST...
                alive.remove(3);                              // ...e morreu antes do REPLY
                return false;
            }
            @Override public void sendReply(int to, long ts) {}
        };
        RicartAgrawala ra = new RicartAgrawala(1, new LamportClock(), t);
        Thread th = Thread.ofVirtual().start(() -> {
            try { ra.acquire(); } catch (InterruptedException ignored) {}
        });
        await(() -> ra.state() == RicartAgrawala.State.HELD);
        ra.release();
        th.join(1000);
        ra.close();
    }

    @Test
    void peerQueVoltaEhRecontatadoNaProximaEntrada() throws InterruptedException {
        Set<Integer> alive = ConcurrentHashMap.newKeySet();
        alive.add(3);
        AtomicInteger calls = new AtomicInteger();
        boolean[] reachable = {false};
        RicartAgrawala[] ra = new RicartAgrawala[1];
        MutexTransport t = new MutexTransport() {
            @Override public Set<Integer> allPeers() { return Set.of(3); }
            @Override public Set<Integer> alivePeers() { return Set.copyOf(alive); }
            @Override public boolean sendRequest(int to, long ts) {
                calls.incrementAndGet();
                if (!reachable[0]) {
                    alive.remove(3);
                    return false;
                }
                ra[0].onReply(3, ts);
                return true;
            }
            @Override public void sendReply(int to, long ts) {}
        };
        ra[0] = new RicartAgrawala(1, new LamportClock(), t);

        ra[0].acquire(); // peer falha e fica em quarentena
        ra[0].release();
        reachable[0] = true;
        ra[0].acquire(); // deve recontatar o peer que voltou
        ra[0].release();

        assertEquals(2, calls.get(), "peer recuperado precisa ser recontatado");
        ra[0].close();
    }
}
