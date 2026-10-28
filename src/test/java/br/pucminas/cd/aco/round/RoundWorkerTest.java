package br.pucminas.cd.aco.round;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import br.pucminas.cd.aco.aco.AntColony;
import br.pucminas.cd.aco.aco.BestTracker;
import br.pucminas.cd.aco.aco.PheromoneMatrix;
import br.pucminas.cd.aco.aco.TspInstance;
import br.pucminas.cd.aco.clock.LamportClock;
import br.pucminas.cd.aco.config.NodeConfig;
import br.pucminas.cd.aco.mutex.MutexTransport;
import br.pucminas.cd.aco.mutex.RicartAgrawala;
import br.pucminas.cd.aco.rpc.PeerNetwork;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RoundWorkerTest {

    /** Sem peers: a seção crítica é local e o broadcast não envia nada. */
    private static final class NoPeersTransport implements MutexTransport {
        @Override public Set<Integer> allPeers() { return Set.of(); }
        @Override public Set<Integer> alivePeers() { return Set.of(); }
        @Override public boolean sendRequest(int to, long timestamp) { return true; }
        @Override public void sendReply(int to, long requestTs) {}
    }

    @Test
    void rodadaRepetidaNaoAtualizaFeromonioDeNovo() throws Exception {
        int cities = 6;
        double rho = 0.1;
        TspInstance tsp = TspInstance.random(cities, 42);
        PheromoneMatrix tau = new PheromoneMatrix(cities, 1.0 / (rho * tsp.nearestNeighborLength()));
        AntColony colony = new AntColony(tsp, 1.0, 3.0);
        BestTracker best = new BestTracker();
        LamportClock clock = new LamportClock();
        PeerNetwork network = new PeerNetwork(1, clock, Map.of(), "t");
        RicartAgrawala mutex = new RicartAgrawala(1, clock, new NoPeersTransport());
        NodeConfig cfg = new NodeConfig(1, 50051, Map.of(), "t", null, cities, 42, 10, 3,
                1.0, 3.0, rho, 0, NodeConfig.Timeouts.defaults());
        AtomicInteger done = new AtomicInteger();
        RoundWorker worker = new RoundWorker(cfg, colony, tau, best, mutex, network,
                (round, from, tour) -> done.incrementAndGet());
        try {
            worker.onStartRound(5, 1);
            await(done, 1);
            double[] afterFirst = tau.flatten();

            // Novo líder que estava fora recomeça na rodada 1, por exemplo: não pode refazer
            // a atualização de feromônio nem deixar o líder esperando.
            worker.onStartRound(5, 1);
            await(done, 2);

            assertArrayEquals(afterFirst, tau.flatten(), 0.0);
        } finally {
            worker.close();
            mutex.close();
            network.close();
        }
    }

    private static void await(AtomicInteger counter, int value) throws InterruptedException {
        for (int i = 0; i < 200 && counter.get() < value; i++) Thread.sleep(10);
        assertEquals(value, counter.get());
    }
}
