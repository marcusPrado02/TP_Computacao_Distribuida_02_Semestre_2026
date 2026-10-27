package br.pucminas.cd.aco.rpc;

import br.pucminas.cd.aco.aco.Tour;
import br.pucminas.cd.aco.clock.LamportClock;
import br.pucminas.cd.aco.election.ElectionTransport;
import br.pucminas.cd.aco.grpc.Ack;
import br.pucminas.cd.aco.grpc.CoordinatorMsg;
import br.pucminas.cd.aco.grpc.CsReply;
import br.pucminas.cd.aco.grpc.CsRequest;
import br.pucminas.cd.aco.grpc.ElectionMsg;
import br.pucminas.cd.aco.grpc.ElectionReply;
import br.pucminas.cd.aco.grpc.Header;
import br.pucminas.cd.aco.grpc.HeartbeatMsg;
import br.pucminas.cd.aco.grpc.NodeServiceGrpc;
import br.pucminas.cd.aco.grpc.NodeServiceGrpc.NodeServiceBlockingStub;
import br.pucminas.cd.aco.grpc.PheromoneUpdate;
import br.pucminas.cd.aco.grpc.RoundResult;
import br.pucminas.cd.aco.grpc.RoundStart;
import br.pucminas.cd.aco.mutex.MutexTransport;
import br.pucminas.cd.aco.util.Log;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Lado cliente do gRPC: canais para cada peer, carimbo de Lamport em toda mensagem
 * e visão de quais peers estão vivos (atualizada a cada chamada).
 */
public final class PeerNetwork implements MutexTransport, ElectionTransport, AutoCloseable {
    /** Padrões para loopback/compose; em LAN o nó usa os valores de {@code NodeConfig.Timeouts}. */
    static final long DEFAULT_FAST = 500;
    static final long DEFAULT_BROADCAST = 2_000;

    private final int selfId;
    private final LamportClock clock;
    private final long fastTimeoutMs;
    private final long broadcastTimeoutMs;
    private final Map<Integer, ManagedChannel> channels = new HashMap<>();
    private final Map<Integer, NodeServiceBlockingStub> stubs = new HashMap<>();
    private final Set<Integer> alive = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public PeerNetwork(int selfId, LamportClock clock, Map<Integer, String> peers, String clusterToken) {
        this(selfId, clock, peers, clusterToken, DEFAULT_FAST, DEFAULT_BROADCAST);
    }

    public PeerNetwork(int selfId, LamportClock clock, Map<Integer, String> peers, String clusterToken,
                       long fastTimeoutMs, long broadcastTimeoutMs) {
        this.selfId = selfId;
        this.clock = clock;
        this.fastTimeoutMs = fastTimeoutMs;
        this.broadcastTimeoutMs = broadcastTimeoutMs;
        peers.forEach((id, address) -> {
            ManagedChannel channel = ManagedChannelBuilder.forTarget(address).usePlaintext().build();
            channels.put(id, channel);
            stubs.put(id, NodeServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(ClusterAuth.clientInterceptor(clusterToken)));
            alive.add(id);
        });
    }

    /** Cabeçalho de toda mensagem enviada: o envio é um evento, então incrementa o relógio. */
    public Header header() {
        return Header.newBuilder().setSenderId(selfId).setLamport(clock.tick()).build();
    }

    // ---------------------------------------------------------------- Eleição

    @Override
    public Set<Integer> allPeers() {
        return stubs.keySet();
    }

    @Override
    public boolean sendElection(int to) {
        return call(to, fastTimeoutMs,
                s -> s.election(ElectionMsg.newBuilder().setHeader(header()).build()),
                ElectionReply::getHeader)
                .map(ElectionReply::getOk)
                .orElse(false);
    }

    @Override
    public void broadcastCoordinator() {
        for (int id : stubs.keySet()) {
            executor.submit(() -> ack(id, fastTimeoutMs, s -> s.coordinator(
                    CoordinatorMsg.newBuilder().setHeader(header()).setLeaderId(selfId).build())));
        }
    }

    @Override
    public boolean heartbeat(int to) {
        return ack(to, fastTimeoutMs, s -> s.heartbeat(HeartbeatMsg.newBuilder().setHeader(header()).build()));
    }

    // ---------------------------------------------------------------- Exclusão mútua

    @Override
    public Set<Integer> alivePeers() {
        return Set.copyOf(alive);
    }

    @Override
    public boolean sendRequest(int to, long timestamp) {
        return ack(to, fastTimeoutMs, s -> s.requestCS(
                CsRequest.newBuilder().setHeader(header()).setRequestTs(timestamp).build()));
    }

    @Override
    public void sendReply(int to, long requestTs) {
        executor.submit(() -> ack(to, fastTimeoutMs,
                s -> s.replyCS(CsReply.newBuilder().setHeader(header()).setRequestTs(requestTs).build())));
    }

    // ---------------------------------------------------------------- Rodadas ACO

    public void broadcastStartRound(int round) {
        for (int id : stubs.keySet()) {
            executor.submit(() -> ack(id, fastTimeoutMs, s -> s.startRound(
                    RoundStart.newBuilder().setHeader(header()).setRound(round).build())));
        }
    }

    public void sendRoundDone(int leader, int round, Tour tour) {
        ack(leader, fastTimeoutMs, s -> s.roundDone(RoundResult.newBuilder()
                .setHeader(header())
                .setRound(round)
                .addAllTour(Protos.boxed(tour.cities()))
                .setLength(tour.length())
                .build()));
    }

    /** Envia a matriz a todos os peers e espera as confirmações (chamado dentro da seção crítica). */
    public void broadcastPheromone(int round, double[] tau, Tour best) {
        PheromoneUpdate base = PheromoneUpdate.newBuilder()
                .setRound(round)
                .addAllTau(Protos.boxed(tau))
                .addAllBestTour(Protos.boxed(best.cities()))
                .setBestLength(best.length())
                .build();
        List<Future<Boolean>> pending = stubs.keySet().stream()
                // Peer marcado como morto não deve custar BROADCAST_TIMEOUT_MS em toda rodada.
                // Trade-off: se ele só estava lento, perde esta atualização (ver "Limitações").
                .filter(alive::contains)
                .map(id -> executor.submit(() -> ack(id, broadcastTimeoutMs,
                        s -> s.updatePheromone(base.toBuilder().setHeader(header()).build()))))
                .toList();
        for (Future<Boolean> f : pending) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                Log.info("Falha no broadcast de feromônio: %s", e.getCause());
            }
        }
    }

    // ---------------------------------------------------------------- Infra

    private boolean ack(int peer, long timeoutMs, Function<NodeServiceBlockingStub, Ack> rpc) {
        return call(peer, timeoutMs, rpc, Ack::getHeader).isPresent();
    }

    /** Executa um RPC com deadline; mescla o relógio da resposta e atualiza o conjunto de vivos. */
    private <T> Optional<T> call(int peer, long timeoutMs,
                                 Function<NodeServiceBlockingStub, T> rpc, Function<T, Header> headerOf) {
        try {
            T response = rpc.apply(stubs.get(peer).withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS));
            clock.onReceive(headerOf.apply(response).getLamport());
            if (alive.add(peer)) Log.info("N%d voltou a responder", peer);
            return Optional.of(response);
        } catch (StatusRuntimeException e) {
            if (alive.remove(peer)) Log.info("N%d inacessível (%s)", peer, e.getStatus().getCode());
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        channels.values().forEach(ManagedChannel::shutdownNow);
    }
}
