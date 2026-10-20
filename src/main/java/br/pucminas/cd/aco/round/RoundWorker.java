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
 * formigas locais, depois seção crítica (atualiza + propaga feromônio), depois avisa o líder.
 *
 * <p>A parte cara (formigas) roda em paralelo em todos os nós e fora da seção crítica; só a escrita
 * na matriz global é serializada. É isso que torna a distribuição vantajosa.
 *
 * <p>Thread-safety: as rodadas executam numa única thread ({@code executor}), uma de cada vez e na
 * ordem de chegada. {@link #onStartRound} e {@link #onPheromoneUpdate} são chamados pelos handlers
 * gRPC e só enfileiram trabalho ou mexem em objetos thread-safe.
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

    /** O líder disparou a rodada {@code round}: enfileira a execução. */
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
            // Semente distinta por (nó, rodada): nós diferentes exploram rotas diferentes,
            // e a mesma configuração reproduz a mesma execução.
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
                // Loga antes de liberar: depois do release() outro nó pode entrar e logar ENTROU,
                // e os logs mostrariam uma sobreposição que não existiu.
                Log.info("Rodada %d: SAIU da seção crítica", round);
                mutex.release();
            }
            reportDone(round, local);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.info("Erro na rodada %d: %s", round, e);
        }
    }

    /** Avisa o líder; se o líder é este nó, entrega direto ao coordenador local. */
    private void reportDone(int round, Tour local) {
        int leader = leaderId.getAsInt();
        if (leader == cfg.nodeId()) {
            localSink.onRoundDone(round, cfg.nodeId(), local);
        } else if (leader > 0) {
            network.sendRoundDone(leader, round, local);
        }
    }
}
