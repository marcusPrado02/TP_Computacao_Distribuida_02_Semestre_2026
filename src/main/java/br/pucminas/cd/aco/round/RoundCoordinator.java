package br.pucminas.cd.aco.round;

import br.pucminas.cd.aco.aco.BestTracker;
import br.pucminas.cd.aco.aco.Tour;
import br.pucminas.cd.aco.config.NodeConfig;
import br.pucminas.cd.aco.rpc.PeerNetwork;
import br.pucminas.cd.aco.util.Log;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Papel de líder: dispara rodadas (StartRound), espera os RoundDone dos nós vivos
 * e acompanha a melhor rota global. Só roda enquanto este nó for o líder eleito.
 *
 * <p>As rodadas são síncronas (barreira): a rodada k+1 só começa quando todos os participantes
 * vivos terminaram a k. Assim todos usam a mesma "geração" de feromônio, como no ACO sequencial.
 * Um participante que cai deixa de ser esperado; se ninguém responde, o timeout encerra a rodada.
 *
 * <p>Thread-safety: o laço roda numa thread dedicada; {@link #onRoundDone} chega pelos handlers gRPC
 * (e pelo trabalhador local) e acorda a espera pelo monitor de {@code this}. {@code done} é
 * concorrente porque é escrito pelos handlers e lido pelo laço.
 */
public final class RoundCoordinator implements RoundSink, AutoCloseable {
    /** Tempo máximo de uma rodada; protege o líder de esperar para sempre por um nó travado. */
    private static final long ROUND_TIMEOUT_MS = 30_000;
    /** Intervalo para reavaliar quem está vivo durante a espera (a queda não gera notificação). */
    private static final long POLL_MS = 500;

    private final NodeConfig cfg;
    private final PeerNetwork network;
    private final BestTracker best;
    private final BooleanSupplier isLeader;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    /** rodada -&gt; ids dos nós que já enviaram RoundDone. */
    private final Map<Integer, Set<Integer>> done = new ConcurrentHashMap<>();
    private RoundWorker worker;

    public RoundCoordinator(NodeConfig cfg, PeerNetwork network, BestTracker best, BooleanSupplier isLeader) {
        this.cfg = cfg;
        this.network = network;
        this.best = best;
        this.isLeader = isLeader;
    }

    /** Liga o trabalhador local (dependência circular líder &lt;-&gt; trabalhador no mesmo nó). */
    public void attach(RoundWorker worker) {
        this.worker = worker;
    }

    /** Chamado quando este nó vence a eleição. */
    public void onBecameLeader() {
        if (running.compareAndSet(false, true)) {
            executor.submit(this::loop);
        }
    }

    /** Laço do líder: dispara rodadas até acabar ou perder a liderança. */
    private void loop() {
        try {
            Thread.sleep(cfg.startDelayMs()); // dá tempo dos peers subirem / eleição estabilizar
            int round = worker.lastRound() + 1; // novo líder continua de onde o anterior parou
            while (isLeader.getAsBoolean() && round <= cfg.rounds()) {
                Set<Integer> participants = new HashSet<>(network.alivePeers());
                participants.add(cfg.nodeId());
                done.put(round, ConcurrentHashMap.newKeySet());
                Log.info("LÍDER: iniciando rodada %d/%d com nós %s", round, cfg.rounds(), participants);

                network.broadcastStartRound(round);
                worker.onStartRound(round);
                awaitRound(round, participants);

                Tour global = best.get();
                Log.info("LÍDER: rodada %d concluída; melhor global = %.0f",
                        round, global == null ? Double.NaN : global.length());
                round++;
            }
            if (round > cfg.rounds()) {
                finished.set(true);
                Tour global = best.get();
                if (global == null) {
                    Log.info("FIM: nenhuma rota recebida");
                } else {
                    Log.info("FIM: melhor rota = %.0f %s", global.length(), Arrays.toString(global.cities()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running.set(false);
        }
    }

    /** true depois que este nó, como líder, concluiu a última rodada. */
    public boolean isFinished() {
        return finished.get();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /** Espera RoundDone de todos os participantes que continuam vivos (ou timeout). */
    private void awaitRound(int round, Set<Integer> participants) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ROUND_TIMEOUT_MS;
        synchronized (this) {
            while (isLeader.getAsBoolean() && System.currentTimeMillis() < deadline) {
                Set<Integer> pending = new HashSet<>(participants);
                pending.removeAll(done.get(round));
                pending.retainAll(aliveWithSelf());
                if (pending.isEmpty()) return;
                wait(POLL_MS);
            }
        }
        Log.info("LÍDER: rodada %d encerrada por timeout", round);
    }

    private Set<Integer> aliveWithSelf() {
        Set<Integer> alive = new HashSet<>(network.alivePeers());
        alive.add(cfg.nodeId());
        return alive;
    }

    @Override
    public void onRoundDone(int round, int from, Tour tour) {
        done.computeIfAbsent(round, r -> ConcurrentHashMap.newKeySet()).add(from);
        best.offer(tour);
        synchronized (this) {
            notifyAll();
        }
    }
}
