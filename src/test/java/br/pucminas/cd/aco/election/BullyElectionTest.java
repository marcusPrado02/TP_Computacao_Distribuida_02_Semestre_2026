package br.pucminas.cd.aco.election;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class BullyElectionTest {

    static final class FakeTransport implements ElectionTransport {
        final Set<Integer> peers;
        final Set<Integer> aliveHigher;
        final Set<Integer> electionsSent = ConcurrentHashMap.newKeySet();
        final AtomicBoolean coordinatorBroadcast = new AtomicBoolean();

        FakeTransport(Set<Integer> peers, Set<Integer> aliveHigher) {
            this.peers = peers;
            this.aliveHigher = aliveHigher;
        }

        @Override public Set<Integer> allPeers() { return peers; }
        @Override public boolean sendElection(int to) { electionsSent.add(to); return aliveHigher.contains(to); }
        @Override public void broadcastCoordinator() { coordinatorBroadcast.set(true); }
        @Override public boolean heartbeat(int to) { return true; }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condição não satisfeita a tempo");
    }

    @Test
    void semMaioresVivosViraLider() throws InterruptedException {
        FakeTransport t = new FakeTransport(Set.of(1, 3), Set.of());
        BullyElection e = new BullyElection(2, t);
        e.startElection();
        await(e::isLeader);
        assertTrue(t.coordinatorBroadcast.get());
        assertFalse(t.electionsSent.contains(1), "não envia ELECTION para id menor");
    }

    @Test
    void maiorVivoRespondeEAnunciaCoordenador() throws InterruptedException {
        FakeTransport t = new FakeTransport(Set.of(1, 3), Set.of(3));
        BullyElection e = new BullyElection(2, t);
        e.startElection();
        await(() -> t.electionsSent.contains(3));
        e.onCoordinator(3);
        assertEquals(3, e.leaderId());
        assertFalse(t.coordinatorBroadcast.get());
    }

    @Test
    void electionDeMenorRespondeOkEDisputa() throws InterruptedException {
        FakeTransport t = new FakeTransport(Set.of(1, 3), Set.of());
        BullyElection e = new BullyElection(2, t);
        assertTrue(e.onElection(1));
        assertFalse(e.onElection(3));
        await(e::isLeader);
    }
}
