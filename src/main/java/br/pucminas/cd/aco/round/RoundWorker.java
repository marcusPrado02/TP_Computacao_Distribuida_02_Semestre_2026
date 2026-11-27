package br.pucminas.cd.aco.round;

import br.pucminas.cd.aco.aco.AntColony;
import br.pucminas.cd.aco.aco.BestTracker;
import br.pucminas.cd.aco.aco.PheromoneMatrix;
import br.pucminas.cd.aco.aco.Tour;
import br.pucminas.cd.aco.config.NodeConfig;
import br.pucminas.cd.aco.mutex.RicartAgrawala;
import br.pucminas.cd.aco.replica.Checkpoint;
import br.pucminas.cd.aco.replica.ReplicaSnapshot;
import br.pucminas.cd.aco.replica.Version;
import br.pucminas.cd.aco.rpc.PeerNetwork;
import br.pucminas.cd.aco.util.Log;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Papel de trabalhador (todo nó, inclusive o líder). A cada rodada:
 * formigas locais, depois seção crítica (atualiza + propaga feromônio), depois avisa quem iniciou
 * a rodada (<code>coordinator</code>).
 *
 * <p>A parte cara (formigas) roda em paralelo em todos os nós e fora da seção crítica; só a escrita
 * na matriz global é serializada. É isso que torna a distribuição vantajosa.
 *
 * <p><b>Réplica e recuperação (Entrega 2).</b> Este nó guarda uma réplica versionada da matriz
 * ({@link PheromoneMatrix#version()}). Ele se mantém atualizado por três caminhos:
 * <ol>
 *   <li>UPDATE de quem sai da seção crítica: aplicado só se a versão for mais nova;</li>
 *   <li>catch-up no início da rodada: o {@code StartRound} traz a versão do líder, e quem está atrás
 *       pede o estado a ele ({@code FetchState}) antes de rodar as formigas;</li>
 *   <li>catch-up depois da própria escrita: se algum peer respondeu ao broadcast com versão mais
 *       nova, esta escrita partiu de base velha, e o nó busca o estado daquele peer.</li>
 * </ol>
 * Na subida (nó que volta) e quando vira líder, {@link #syncFromCluster} pega o estado mais novo
 * entre todos os peers.
 *
 * <p>Thread-safety: as rodadas executam numa única thread ({@code executor}), uma de cada vez e na
 * ordem de chegada. {@link #onStartRound} e {@link #onPheromoneUpdate} são chamados pelos handlers
 * gRPC e só enfileiram trabalho ou mexem em objetos thread-safe; o controle de rodada
 * ({@code startedRound}, {@code runningRound}, {@code reportTo}) é protegido por {@code this}.
 */
public final class RoundWorker implements AutoCloseable {
    private final NodeConfig cfg;
    private final AntColony colony;
    private final PheromoneMatrix pheromone;
    private final BestTracker best;
    private final RicartAgrawala mutex;
    private final PeerNetwork network;
    private final RoundSink localSink;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(); // uma rodada por vez
    /** Maior rodada vista (por StartRound, UPDATE ou estado transferido); o novo líder continua dela. */
    private final AtomicInteger lastSeen = new AtomicInteger(0);
    private volatile Checkpoint checkpoint;

    // Protegidos por this
    /** Maior rodada que este nó começou a executar (deduplica StartRound repetido). */
    private int startedRound;
    /** Rodada em execução agora, ou 0. */
    private int runningRound;
    /** Para quem vai o RoundDone da rodada em execução (muda se outro líder reemitir a rodada). */
    private int reportTo;

    public RoundWorker(NodeConfig cfg, AntColony colony, PheromoneMatrix pheromone, BestTracker best,
                       RicartAgrawala mutex, PeerNetwork network, RoundSink localSink) {
        this.cfg = cfg;
        this.colony = colony;
        this.pheromone = pheromone;
        this.best = best;
        this.mutex = mutex;
        this.network = network;
        this.localSink = localSink;
    }

    /** Liga a persistência: toda mudança de estado agenda uma gravação. */
    public void setCheckpoint(Checkpoint checkpoint) {
        this.checkpoint = checkpoint;
    }

    /** Última rodada vista; um novo líder continua a partir dela. */
    public int lastRound() {
        return lastSeen.get();
    }

    /** Versão da réplica local da matriz. */
    public Version version() {
        return pheromone.version();
    }

    /** Estado completo desta réplica (para FetchState e checkpoint). */
    public ReplicaSnapshot snapshot() {
        PheromoneMatrix.Versioned v = pheromone.versioned();
        return new ReplicaSnapshot(v.version(), v.tau(), best.get(), lastSeen.get());
    }

    /** Recebeu StartRound sem versão (testes): não faz catch-up. */
    public void onStartRound(int round, int coordinator) {
        onStartRound(round, coordinator, -1);
    }

    /**
     * {@code coordinator} é quem enviou o StartRound; é para ele que o RoundDone volta.
     * {@code coordinatorVersion} é a versão da réplica dele (-1 = desconhecida).
     *
     * <p>Rodada repetida (novo líder retomando a rodada interrompida, ou dois líderes) não refaz o
     * trabalho: se já terminou, responde na hora; se ainda está rodando, só redireciona o RoundDone
     * para o novo coordenador — o antigo pode ter morrido.
     */
    public void onStartRound(int round, int coordinator, long coordinatorVersion) {
        lastSeen.accumulateAndGet(round, Math::max);
        synchronized (this) {
            if (round > startedRound) {
                startedRound = round;
                runningRound = round;
                reportTo = coordinator;
                executor.submit(() -> runRound(round, coordinatorVersion));
                return;
            }
            if (round == runningRound) {
                reportTo = coordinator;
                return;
            }
        }
        reportDone(round, best.get(), coordinator);
    }

    /**
     * Outro nó saiu da seção crítica e propagou a matriz. Só aplica se o carimbo for mais novo.
     *
     * @return o que a réplica fez (vai na resposta, para o escritor saber se estava atrasado)
     */
    public PheromoneMatrix.Applied onPheromoneUpdate(int round, double[] tau, Version version, Tour senderBest) {
        lastSeen.accumulateAndGet(round, Math::max);
        PheromoneMatrix.Applied result = pheromone.applyIfNewer(tau, version);
        best.offer(senderBest);
        if (result.conflict()) {
            Log.info("CONFLITO de réplica: recebido %s, local era %s (duas escritas sobre a mesma versão); "
                    + "fica a de maior carimbo: %s", version, result.applied() ? "anterior" : result.local(),
                    pheromone.version());
        } else if (!result.applied()) {
            Log.info("UPDATE %s ignorado: réplica já está em %s", version, result.local());
        }
        dirty();
        return result;
    }

    /**
     * Instala um estado recebido (FetchState) ou lido do checkpoint, se for mais novo.
     *
     * @return true se a matriz local foi substituída
     */
    public boolean install(ReplicaSnapshot s, String source) {
        lastSeen.accumulateAndGet(s.lastRound(), Math::max);
        best.offer(s.best());
        PheromoneMatrix.Applied result = pheromone.applyIfNewer(s.tau(), s.version());
        if (result.applied()) {
            Log.info("Réplica atualizada por %s: versão %s, rodada %d", source, s.version(), s.lastRound());
        }
        dirty();
        return result.applied();
    }

    /**
     * Pede o estado a todos os peers e fica com o mais novo (recuperação de quem volta e
     * preparação do novo líder).
     *
     * @return quantos peers responderam
     */
    public int syncFromCluster() {
        List<ReplicaSnapshot> states = network.fetchStateFromAll();
        for (ReplicaSnapshot s : states) install(s, "sincronização");
        Log.info("Sincronização: %d peer(s) responderam; réplica em %s, última rodada vista %d",
                states.size(), pheromone.version(), lastSeen.get());
        return states.size();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void runRound(int round, long coordinatorVersion) {
        try {
            // Réplica atrás do líder (perdeu UPDATEs enquanto estava em quarentena, ou acabou de
            // voltar): busca o estado antes de gastar a rodada sobre uma matriz velha.
            if (coordinatorVersion > pheromone.version().number()) {
                int from = currentReportTo();
                Log.info("Rodada %d: réplica em %s, líder em v%d; catch-up com N%d",
                        round, pheromone.version(), coordinatorVersion, from);
                if (from != cfg.nodeId()) network.fetchState(from).ifPresent(s -> install(s, "catch-up"));
            }

            // Semente distinta por (nó, rodada): nós diferentes exploram rotas diferentes,
            // e a mesma configuração reproduz a mesma execução.
            long seed = cfg.tspSeed() * 1_000_003L + round * 101L + cfg.nodeId();
            // ITERATIONS_PER_ROUND lotes sobre o MESMO snapshot, ficando com a melhor rota: alonga só a
            // parte local (paralela, fora da SC) sem mudar o protocolo nem o custo de rede da rodada.
            // A iteração 0 usa a semente original, então o padrão (1) reproduz a execução anterior.
            double[][] tau = pheromone.snapshot();
            Tour local = null;
            for (int it = 0; it < cfg.iterationsPerRound(); it++) {
                Tour candidate = colony.runAnts(tau, cfg.ants(), seed + it * 7_919L);
                if (local == null || candidate.length() < local.length()) local = candidate;
            }
            Log.info("Rodada %d: melhor local = %.0f", round, local.length());

            mutex.acquire();
            try {
                Log.info("Rodada %d: ENTROU na seção crítica", round);
                Version written = pheromone.evaporateAndDeposit(local, cfg.rho(), cfg.nodeId());
                best.offer(local);
                // Matriz e carimbo lidos juntos: se um UPDATE concorrente (exclusão violada) chegou
                // entre a escrita e o envio, propaga o par consistente mais novo, nunca uma mistura.
                PheromoneMatrix.Versioned out = pheromone.versioned();
                // Síncrono: só libera depois que todos confirmarem a nova matriz.
                int newer = network.broadcastPheromone(round, out.tau(), out.version(), best.get(),
                        currentReportTo());
                dirty();
                if (newer > 0) {
                    Log.info("Rodada %d: escrita %s partiu de réplica atrasada (N%d tem versão mais nova); catch-up",
                            round, written, newer);
                    network.fetchState(newer).ifPresent(s -> install(s, "catch-up"));
                }
            } finally {
                // Loga antes de liberar: depois do release() outro nó pode entrar e logar ENTROU,
                // e os logs mostrariam uma sobreposição que não existiu.
                Log.info("Rodada %d: SAIU da seção crítica", round);
                mutex.release();
            }
            int target;
            synchronized (this) {
                target = reportTo;
                if (runningRound == round) runningRound = 0;
            }
            reportDone(round, local, target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.info("Erro na rodada %d: %s", round, e);
            synchronized (this) {
                if (runningRound == round) runningRound = 0;
            }
        }
    }

    private synchronized int currentReportTo() {
        return reportTo;
    }

    private void dirty() {
        Checkpoint c = checkpoint;
        if (c != null) c.markDirty();
    }

    /** Avisa quem iniciou a rodada; se for este nó, entrega direto ao coordenador local. */
    private void reportDone(int round, Tour local, int coordinator) {
        // Rodada repetida pode chegar sem nenhuma rota conhecida (nó reiniciado): manda vazio.
        Tour result = local != null ? local : new Tour(new int[0], 0);
        if (coordinator == cfg.nodeId()) {
            localSink.onRoundDone(round, cfg.nodeId(), result);
        } else if (coordinator > 0) {
            network.sendRoundDone(coordinator, round, result);
        }
    }
}
