package br.pucminas.cd.aco;

import br.pucminas.cd.aco.aco.AntColony;
import br.pucminas.cd.aco.aco.BestTracker;
import br.pucminas.cd.aco.aco.PheromoneMatrix;
import br.pucminas.cd.aco.aco.Tour;
import br.pucminas.cd.aco.aco.TspInstance;
import br.pucminas.cd.aco.clock.LamportClock;
import br.pucminas.cd.aco.config.NodeConfig;
import br.pucminas.cd.aco.election.BullyElection;
import br.pucminas.cd.aco.election.LeaderMonitor;
import br.pucminas.cd.aco.mutex.RicartAgrawala;
import br.pucminas.cd.aco.round.RoundCoordinator;
import br.pucminas.cd.aco.round.RoundWorker;
import br.pucminas.cd.aco.rpc.ClusterAuth;
import br.pucminas.cd.aco.rpc.NodeServiceImpl;
import br.pucminas.cd.aco.rpc.PeerNetwork;
import br.pucminas.cd.aco.util.Log;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.util.TransmitStatusRuntimeExceptionInterceptor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Um nó do cluster: monta e liga todos os módulos (composition root).
 *
 * <p>Todos os nós são iguais; o papel de líder é decidido em tempo de execução pelo Bully.
 * Ciclo de vida: {@link #start()} sobe o servidor gRPC, o monitor de líder e a primeira eleição;
 * {@link #close()} derruba tudo na ordem inversa.
 */
public final class Node implements AutoCloseable {
    /** Janela do handshake de startup; cobre peers que ainda estão subindo. */
    private static final long HANDSHAKE_RETRY_MS = 500;
    private static final long HANDSHAKE_MIN_WINDOW_MS = 3_000;

    private final NodeConfig cfg;
    private final PheromoneMatrix pheromone;
    private final BestTracker best = new BestTracker();
    private final PeerNetwork network;
    private final BullyElection election;
    private final LeaderMonitor monitor;
    private final RicartAgrawala mutex;
    private final RoundCoordinator coordinator;
    private final RoundWorker worker;
    private final Server server;
    private volatile boolean closed;

    public Node(NodeConfig cfg) throws IOException {
        this.cfg = cfg;
        LamportClock clock = new LamportClock();
        Log.init(cfg.nodeId(), clock::now);

        TspInstance tsp = cfg.tspFile() != null
                ? TspInstance.fromTsplib(Path.of(cfg.tspFile()))
                : TspInstance.random(cfg.tspCities(), cfg.tspSeed());
        double nearestNeighbor = tsp.nearestNeighborLength();
        if (!(nearestNeighbor > 0) || !Double.isFinite(nearestNeighbor)) {
            throw new IllegalArgumentException(
                    "instância TSP degenerada: todas as cidades coincidem (vizinho mais próximo = 0)");
        }
        Log.info("TSP com %d cidades; vizinho mais próximo = %.0f", tsp.size(), nearestNeighbor);

        // τ0 = 1 / (ρ · L_nn), inicialização usual do MAX-MIN Ant System
        this.pheromone = new PheromoneMatrix(tsp.size(), 1.0 / (cfg.rho() * nearestNeighbor));
        AntColony colony = new AntColony(tsp, cfg.alpha(), cfg.beta());

        this.network = new PeerNetwork(cfg.nodeId(), clock, cfg.peers(), cfg.clusterToken());
        this.network.setConfigErrorHandler(this::fatalConfigError);
        this.election = new BullyElection(cfg.nodeId(), network);
        this.monitor = new LeaderMonitor(cfg.nodeId(), election, network);
        this.mutex = new RicartAgrawala(cfg.nodeId(), clock, network);

        this.coordinator = new RoundCoordinator(cfg, network, best, election::isLeader);
        this.worker = new RoundWorker(cfg, colony, pheromone, best, mutex, network, coordinator);
        coordinator.attach(worker);
        election.onLeaderChange(leader -> {
            if (leader == cfg.nodeId()) coordinator.onBecameLeader();
        });

        NodeServiceImpl service = new NodeServiceImpl(clock, network, election, mutex, worker, coordinator);
        this.server = ServerBuilder.forPort(cfg.port())
                .maxInboundMessageSize(PeerNetwork.MAX_MESSAGE_BYTES)
                .addService(ServerInterceptors.intercept(service,
                        // Repassa Status lançados nos handlers (ex.: PERMISSION_DENIED) em vez de UNKNOWN
                        TransmitStatusRuntimeExceptionInterceptor.instance(),
                        ClusterAuth.serverInterceptor(cfg.clusterToken())))
                .build();
    }

    /** Sobe o servidor gRPC, valida a configuração em background e entra no cluster. */
    public void start() throws IOException {
        server.start();
        Log.info("N%d ouvindo na porta %d; peers = %s", cfg.nodeId(), cfg.port(), cfg.peers());
        verifyPeers();
        monitor.start();
        election.startElection();
    }

    /**
     * Handshake de startup: token, remetente e conjunto de membros precisam bater. Um token errado
     * devolve UNAUTHENTICATED, um remetente fora de PEERS devolve PERMISSION_DENIED e um conjunto
     * de membros diferente devolve FAILED_PRECONDITION; todos são fatais. Peer que ainda está
     * subindo é tentado de novo até a janela acabar, para o Compose (nós simultâneos) não falhar.
     */
    private void verifyPeers() {
        Set<Integer> peers = new HashSet<>(network.allPeers());
        if (peers.isEmpty()) return;
        long window = Math.max(HANDSHAKE_MIN_WINDOW_MS, cfg.startDelayMs());
        Thread.ofVirtual().name("handshake").start(() -> {
            long deadline = System.currentTimeMillis() + window;
            Set<Integer> pending = new HashSet<>(peers);
            while (!closed && !pending.isEmpty() && System.currentTimeMillis() < deadline) {
                for (int id : List.copyOf(pending)) {
                    if (closed) return;
                    switch (network.probe(id)) {
                        case OK -> pending.remove(id);
                        case AUTH -> {
                            fatalConfigError("N" + id + " recusou o CLUSTER_TOKEN (UNAUTHENTICATED)");
                            return;
                        }
                        case PEERS -> {
                            fatalConfigError("N" + id + " tem configuração de PEERS divergente "
                                    + "(PERMISSION_DENIED/FAILED_PRECONDITION)");
                            return;
                        }
                        default -> { /* UNAVAILABLE/OTHER: tenta de novo dentro da janela */ }
                    }
                }
                if (pending.isEmpty()) break;
                try {
                    Thread.sleep(HANDSHAKE_RETRY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!closed) {
                if (pending.isEmpty()) {
                    Log.info("Handshake OK com %d peer(s)", peers.size());
                } else {
                    Log.info("Handshake: sem resposta de %s; seguindo (podem subir depois)", pending);
                }
            }
        });
    }

    private void fatalConfigError(String message) {
        if (closed) return;
        Log.info("ERRO FATAL DE CONFIGURAÇÃO: %s", message);
        System.exit(2);
    }

    /** Bloqueia até o servidor ser desligado. */
    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    // ------------------------------------------------------------- Observação (demo e testes)

    public int id() {
        return cfg.nodeId();
    }

    public int leaderId() {
        return election.leaderId();
    }

    /** Última rodada vista por este nó. */
    public int lastRound() {
        return worker.lastRound();
    }

    /** true depois que este nó, como líder, concluiu todas as rodadas configuradas. */
    public boolean finishedAsLeader() {
        return coordinator.isFinished();
    }

    /** Cópia da réplica local da matriz de feromônio. */
    public double[] pheromone() {
        return pheromone.flatten();
    }

    /** Melhor rota conhecida por este nó, ou null se ainda não houve rodada. */
    public Tour best() {
        return best.get();
    }

    @Override
    public void close() throws InterruptedException {
        // Para de aceitar trabalho antes de derrubar os módulos que ele usa; só então
        // espera os handlers em voo terminarem.
        closed = true;
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
        monitor.close();
        coordinator.close();
        worker.close();
        election.close();
        mutex.close();
        network.close();
    }
}
