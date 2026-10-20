package br.pucminas.cd.aco.round;

import br.pucminas.cd.aco.aco.AntColony;
import br.pucminas.cd.aco.aco.BestTracker;
import br.pucminas.cd.aco.aco.PheromoneMatrix;
import br.pucminas.cd.aco.aco.Tour;
import br.pucminas.cd.aco.config.NodeConfig;
import br.pucminas.cd.aco.mutex.RicartAgrawala;
import br.pucminas.cd.aco.rpc.PeerNetwork;
import br.pucminas.cd.aco.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * Papel de trabalhador (todo nó, inclusive o líder). A cada rodada:
 * formigas locais -> seção crítica (atualiza + propaga feromônio) -> avisa o líder.
 */
public final class RoundWorker implements AutoCloseable {
    private final NodeConfig cfg;
    private final AntColony colony;
    private final PheromoneMatrix pheromone;
    private final BestTracker best;
    private final RicartAgrawala mutex;
    private final PeerNetwork network;
    private final IntSupplier leaderId;
    private final RoundSink localSink;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(); // uma rodada por vez
    private final AtomicInteger lastRound = new AtomicInteger(0);

    public RoundWorker(NodeConfig cfg, AntColony colony, PheromoneMatrix pheromone, BestTracker best,
                       RicartAgrawala mutex, PeerNetwork network, IntSupplier leaderId, RoundSink localSink) {
        this.cfg = cfg;
        this.colony = colony;
        this.pheromone = pheromone;
        this.best = best;
        this.mutex = mutex;
        this.network = network;
        this.leaderId = leaderId;
        this.localSink = localSink;
    }

    /** Última rodada vista; um novo líder continua a partir dela. */
    public int lastRound() {
        return lastRound.get();
    }

    public void onStartRound(int round) {
        lastRound.accumulateAndGet(round, Math::max);
        executor.submit(() -> runRound(round));
    }

    /** Outro nó saiu da seção crítica e propagou a matriz. */
    public void onPheromoneUpdate(int round, double[] tau, Tour senderBest) {
        lastRound.accumulateAndGet(round, Math::max);
        pheromone.replace(tau);
        best.offer(senderBest);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void runRound(int round) {
        try {
            long seed = cfg.tspSeed() * 1_000_003L + round * 101L + cfg.nodeId();
            Tour local = colony.runAnts(pheromone.snapshot(), cfg.ants(), seed);
            Log.info("Rodada %d: melhor local = %.0f", round, local.length());

            mutex.acquire();
            try {
                Log.info("Rodada %d: ENTROU na seção crítica", round);
                pheromone.evaporateAndDeposit(local, cfg.rho());
                best.offer(local);
                // Síncrono: só libera depois que todos confirmarem a nova matriz.
                network.broadcastPheromone(round, pheromone.flatten(), best.get());
            } finally {
                mutex.release();
                Log.info("Rodada %d: SAIU da seção crítica", round);
            }
            reportDone(round, local);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.info("Erro na rodada %d: %s", round, e);
        }
    }

    private void reportDone(int round, Tour local) {
        int leader = leaderId.getAsInt();
        if (leader == cfg.nodeId()) {
            localSink.onRoundDone(round, cfg.nodeId(), local);
        } else if (leader > 0) {
            network.sendRoundDone(leader, round, local);
        }
    }
}
