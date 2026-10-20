package br.pucminas.cd.aco.mutex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.pucminas.cd.aco.clock.LamportClock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Propriedade de segurança do Ricart-Agrawala com vários nós disputando ao mesmo tempo:
 * nunca há dois nós na seção crítica, e todo pedido acaba atendido (sem deadlock).
 *
 * <p>A rede é simulada em memória com atrasos aleatórios, para forçar intercalações
 * diferentes a cada execução. O relógio de Lamport é atualizado na entrega, como faz a camada gRPC.
 */
class RicartAgrawalaSafetyTest {
    private static final int NODES = 4;
    private static final int ENTRIES_PER_NODE = 30;

    /** Rede em memória entre os nós 1..NODES. */
    static final class InMemoryNetwork {
        final Map<Integer, RicartAgrawala> nodes = new HashMap<>();
        final Map<Integer, LamportClock> clocks = new HashMap<>();
        final ExecutorService wire = Executors.newVirtualThreadPerTaskExecutor();

        MutexTransport transportFor(int self) {
            Set<Integer> others = Set.copyOf(IntStream.rangeClosed(1, NODES).filter(i -> i != self).boxed().toList());
            return new MutexTransport() {
                @Override public Set<Integer> alivePeers() { return others; }

                @Override public boolean sendRequest(int to, long timestamp) {
                    long sentAt = clocks.get(self).tick();
                    jitter();
                    clocks.get(to).onReceive(sentAt);
                    nodes.get(to).onRequest(timestamp, self);
                    return true;
                }

                @Override public void sendReply(int to, long requestTs) {
                    long sentAt = clocks.get(self).tick();
                    wire.submit(() -> {
                        jitter();
                        clocks.get(to).onReceive(sentAt);
                        nodes.get(to).onReply(self, requestTs);
                    });
                }
            };
        }

        private static void jitter() {
            try {
                Thread.sleep(ThreadLocalRandom.current().nextInt(0, 3));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void nuncaHaDoisNosNaSecaoCritica() throws Exception {
        InMemoryNetwork net = new InMemoryNetwork();
        for (int id = 1; id <= NODES; id++) {
            LamportClock clock = new LamportClock();
            net.clocks.put(id, clock);
            net.nodes.put(id, new RicartAgrawala(id, clock, net.transportFor(id)));
        }

        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        AtomicInteger entries = new AtomicInteger();

        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<? extends java.util.concurrent.Future<?>> futures = net.nodes.values().stream()
                    .map(ra -> workers.submit(() -> {
                        for (int k = 0; k < ENTRIES_PER_NODE; k++) {
                            ra.acquire();
                            try {
                                maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                                entries.incrementAndGet();
                                Thread.sleep(ThreadLocalRandom.current().nextInt(0, 2));
                                inside.decrementAndGet();
                            } finally {
                                ra.release();
                            }
                        }
                        return null;
                    }))
                    .toList();
            for (var f : futures) f.get();
        } finally {
            net.wire.shutdownNow();
            net.nodes.values().forEach(RicartAgrawala::close);
        }

        assertEquals(1, maxInside.get(), "dois nós estiveram na seção crítica ao mesmo tempo");
        assertEquals(NODES * ENTRIES_PER_NODE, entries.get());
        assertTrue(net.nodes.values().stream().allMatch(ra -> ra.state() == RicartAgrawala.State.RELEASED));
    }
}
