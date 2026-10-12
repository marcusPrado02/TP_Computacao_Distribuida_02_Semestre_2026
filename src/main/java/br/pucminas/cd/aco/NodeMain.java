package br.pucminas.cd.aco;

import br.pucminas.cd.aco.aco.AntColony;
import br.pucminas.cd.aco.aco.BestTracker;
import br.pucminas.cd.aco.aco.PheromoneMatrix;
import br.pucminas.cd.aco.aco.TspInstance;
import br.pucminas.cd.aco.clock.LamportClock;
import br.pucminas.cd.aco.config.NodeConfig;
import br.pucminas.cd.aco.election.BullyElection;
import br.pucminas.cd.aco.election.LeaderMonitor;
import br.pucminas.cd.aco.mutex.RicartAgrawala;
import br.pucminas.cd.aco.round.RoundCoordinator;
import br.pucminas.cd.aco.round.RoundWorker;
import br.pucminas.cd.aco.rpc.NodeServiceImpl;
import br.pucminas.cd.aco.rpc.PeerNetwork;
import br.pucminas.cd.aco.util.Log;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.nio.file.Path;

/** Ponto de entrada de um nó. Todos os nós rodam este mesmo programa. */
public final class NodeMain {

    public static void main(String[] args) throws Exception {
        NodeConfig cfg = NodeConfig.fromEnv();
        LamportClock clock = new LamportClock();
        Log.init(cfg.nodeId(), clock::now);

        TspInstance tsp = cfg.tspFile() != null
                ? TspInstance.fromTsplib(Path.of(cfg.tspFile()))
                : TspInstance.random(cfg.tspCities(), cfg.tspSeed());
        double nearestNeighbor = tsp.nearestNeighborLength();
        Log.info("TSP com %d cidades; vizinho mais próximo = %.0f", tsp.size(), nearestNeighbor);

        PeerNetwork network = new PeerNetwork(cfg.nodeId(), clock, cfg.peers());
        BullyElection election = new BullyElection(cfg.nodeId(), network);
        RicartAgrawala mutex = new RicartAgrawala(cfg.nodeId(), clock, network);

        BestTracker best = new BestTracker();
        // τ0 = 1 / (ρ · L_nn), inicialização usual do MAX-MIN Ant System
        PheromoneMatrix pheromone = new PheromoneMatrix(tsp.size(), 1.0 / (cfg.rho() * nearestNeighbor));
        AntColony colony = new AntColony(tsp, cfg.alpha(), cfg.beta());

        RoundCoordinator coordinator = new RoundCoordinator(cfg, network, best, election::isLeader);
        RoundWorker worker = new RoundWorker(cfg, colony, pheromone, best, mutex, network,
                election::leaderId, coordinator);
        coordinator.attach(worker);
        election.onLeaderChange(leader -> {
            if (leader == cfg.nodeId()) coordinator.onBecameLeader();
        });

        Server server = ServerBuilder.forPort(cfg.port())
                .addService(new NodeServiceImpl(clock, network, election, mutex, worker, coordinator))
                .build()
                .start();
        Log.info("N%d ouvindo na porta %d; peers = %s", cfg.nodeId(), cfg.port(), cfg.peers());

        new LeaderMonitor(cfg.nodeId(), election, network).start();
        election.startElection();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.shutdownNow();
            network.close();
        }));
        server.awaitTermination();
    }
}
