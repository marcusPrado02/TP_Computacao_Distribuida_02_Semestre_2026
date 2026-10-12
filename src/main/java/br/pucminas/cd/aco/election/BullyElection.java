package br.pucminas.cd.aco.election;

import br.pucminas.cd.aco.util.Log;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Eleição de líder pelo algoritmo Bully: o nó vivo de maior id vira coordenador.
 *
 * <ol>
 *   <li>Nó envia ELECTION a todos com id maior.</li>
 *   <li>Se ninguém responde OK, ele se declara líder e envia COORDINATOR a todos.</li>
 *   <li>Se alguém responde OK, espera o COORDINATOR; se não chegar, recomeça.</li>
 * </ol>
 */
public final class BullyElection {
    private static final long COORDINATOR_TIMEOUT_MS = 3_000;

    private final int selfId;
    private final ElectionTransport transport;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean electing = new AtomicBoolean(false);
    private final List<IntConsumer> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private volatile int leaderId = -1;
    private boolean coordinatorSeen; // protegido por lock

    public BullyElection(int selfId, ElectionTransport transport) {
        this.selfId = selfId;
        this.transport = transport;
    }

    public void onLeaderChange(IntConsumer listener) {
        listeners.add(listener);
    }

    public int leaderId() {
        return leaderId;
    }

    public boolean isLeader() {
        return leaderId == selfId;
    }

    /** Inicia uma eleição em background (ignorado se já houver uma em andamento). */
    public void startElection() {
        if (electing.compareAndSet(false, true)) {
            executor.submit(this::runElection);
        }
    }

    private void runElection() {
        boolean retry = false;
        try {
            retry = !electOrAwaitCoordinator();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            electing.set(false);
        }
        if (retry) {
            Log.info("Nenhum COORDINATOR recebido; reiniciando eleição");
            startElection();
        }
    }

    /** @return true se a eleição terminou com um líder conhecido */
    private boolean electOrAwaitCoordinator() throws InterruptedException {
        synchronized (lock) {
            coordinatorSeen = false;
        }
        Log.info("Eleição iniciada");
        boolean anyOk = false;
        for (int id : transport.allPeers().stream().filter(id -> id > selfId).sorted().toList()) {
            anyOk |= transport.sendElection(id);
        }
        if (!anyOk) {
            setLeader(selfId);
            transport.broadcastCoordinator();
            return true;
        }
        return awaitCoordinator();
    }

    private boolean awaitCoordinator() throws InterruptedException {
        long deadline = System.currentTimeMillis() + COORDINATOR_TIMEOUT_MS;
        synchronized (lock) {
            while (!coordinatorSeen) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return false;
                lock.wait(left);
            }
            return true;
        }
    }

    /** Recebeu ELECTION de {@code from}. @return OK (true) se este nó tem id maior. */
    public boolean onElection(int from) {
        if (from >= selfId) return false;
        startElection();
        return true;
    }

    /** Recebeu COORDINATOR anunciando {@code id} como líder. */
    public void onCoordinator(int id) {
        if (id < selfId) {
            // Um nó menor se declarou líder sem nos ver: contestamos (o "bully").
            Log.info("Nó %d se declarou líder, mas tenho id maior; contestando", id);
            startElection();
            return;
        }
        synchronized (lock) {
            coordinatorSeen = true;
            lock.notifyAll();
        }
        setLeader(id);
    }

    private void setLeader(int id) {
        int old = leaderId;
        leaderId = id;
        if (old != id) {
            Log.info("Novo líder: N%d", id);
            listeners.forEach(l -> l.accept(id));
        }
    }
}
