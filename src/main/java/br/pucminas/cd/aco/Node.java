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
import java.util.concurrent.TimeUnit;

/**
 * Um nó do cluster: monta e liga todos os módulos (composition root).
 *
 * <p>Todos os nós são iguais; o papel de líder é decidido em tempo de execução pelo Bully.
 * Ciclo de vida: {@link #start()} sobe o servidor gRPC, o monitor de líder e a primeira eleição;
 * {@link #close()} derruba tudo na ordem inversa.
 *
 * <p>Fiação principal: {@code PeerNetwork} implementa os transportes do Bully e do Ricart-Agrawala;
 * {@code NodeServiceImpl} recebe as mensagens e as entrega aos mesmos módulos; quando o Bully elege
 * este nó, o {@code RoundCoordinator} começa a disparar rodadas.
 */
public final class Node implements AutoCloseable {
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

    /**
     * Monta o nó sem abrir porta nem falar com ninguém.
     *
     * @throws IOException se o arquivo TSPLIB configurado não puder ser lido
     */
    public Node(NodeConfig cfg) throws IOException {
        this.cfg = cfg;
        LamportClock clock = new LamportClock();
        Log.init(cfg.nodeId(), clock::now);

        TspInstance tsp = cfg.tspFile() != null
                ? TspInstance.fromTsplib(Path.of(cfg.tspFile()))
                : TspInstance.random(cfg.tspCities(), cfg.tspSeed());
        double nearestNeighbor = tsp.nearestNeighborLength();
        Log.info("TSP com %d cidades; vizinho mais próximo = %.0f", tsp.size(), nearestNeighbor);

        // τ0 = 1 / (ρ · L_nn), inicialização usual do MAX-MIN Ant System
        this.pheromone = new PheromoneMatrix(tsp.size(), 1.0 / (cfg.rho() * nearestNeighbor));
        AntColony colony = new AntColony(tsp, cfg.alpha(), cfg.beta());

        this.network = new PeerNetwork(cfg.nodeId(), clock, cfg.peers(), cfg.clusterToken());
        this.election = new BullyElection(cfg.nodeId(), network);
        this.monitor = new LeaderMonitor(cfg.nodeId(), election, network);
        this.mutex = new RicartAgrawala(cfg.nodeId(), clock, network);

        this.coordinator = new RoundCoordinator(cfg, network, best, election::isLeader);
        this.worker = new RoundWorker(cfg, colony, pheromone, best, mutex, network, election::leaderId, coordinator);
        coordinator.attach(worker);
        election.onLeaderChange(leader -> {
            if (leader == cfg.nodeId()) coordinator.onBecameLeader();
        });

        NodeServiceImpl service = new NodeServiceImpl(clock, network, election, mutex, worker, coordinator);
        this.server = ServerBuilder.forPort(cfg.port())
                .addService(ServerInterceptors.intercept(service,
                        // Repassa Status lançados nos handlers (ex.: PERMISSION_DENIED) em vez de UNKNOWN
                        TransmitStatusRuntimeExceptionInterceptor.instance(),
                        ClusterAuth.serverInterceptor(cfg.clusterToken())))
                .build();
    }

    /** Sobe o servidor gRPC e entra no cluster (primeira eleição). */
    public void start() throws IOException {
        server.start();
        Log.info("N%d ouvindo na porta %d; peers = %s", cfg.nodeId(), cfg.port(), cfg.peers());
        monitor.start();
        election.startElection();
    }

    /** Bloqueia até o servidor ser desligado. */
    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    // ------------------------------------------------------------- Observação (demo e testes)

    /** Id deste nó. */
    public int id() {
        return cfg.nodeId();
    }

    /** Líder conhecido por este nó, ou -1. */
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
        // Para de gerar trabalho antes de derrubar a rede que ele usa.
        monitor.close();
        coordinator.close();
        worker.close();
        election.close();
        mutex.close();
        server.shutdownNow();
        network.close();
        server.awaitTermination(5, TimeUnit.SECONDS);
    }
}
