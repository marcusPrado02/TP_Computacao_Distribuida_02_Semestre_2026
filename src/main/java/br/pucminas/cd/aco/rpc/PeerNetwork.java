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
import io.grpc.Status;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Lado cliente do gRPC: canais para cada peer, carimbo de Lamport em toda mensagem
 * e visão de quais peers estão vivos (atualizada a cada chamada).
 */
public final class PeerNetwork implements MutexTransport, ElectionTransport, AutoCloseable {
    private static final long FAST_TIMEOUT_MS = 500;
    private static final long HEARTBEAT_TIMEOUT_MS = 1_000;
    private static final long BROADCAST_TIMEOUT_MS = 2_000;
    /** Intervalo mínimo antes de tentar de novo um peer que falhou (evita 500 ms por rodada). */
    private static final long RETRY_AFTER_MS = 2_000;

    /** Limite de mensagem gRPC. O padrão (4 MiB) estoura a matriz a partir de ~725 cidades. */
    public static final int MAX_MESSAGE_BYTES = 64 * 1024 * 1024;

    private final int selfId;
    private final LamportClock clock;
    private final Map<Integer, ManagedChannel> channels = new HashMap<>();
    private final Map<Integer, NodeServiceBlockingStub> stubs = new HashMap<>();
    private final Set<Integer> alive = ConcurrentHashMap.newKeySet();
    /** Instante a partir do qual vale a pena tentar de novo um peer marcado como inacessível. */
    private final Map<Integer, Long> retryAfter = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public PeerNetwork(int selfId, LamportClock clock, Map<Integer, String> peers, String clusterToken) {
        this.selfId = selfId;
        this.clock = clock;
        peers.forEach((id, address) -> {
            ManagedChannel channel = ManagedChannelBuilder.forTarget(address).usePlaintext().build();
            channels.put(id, channel);
            stubs.put(id, NodeServiceGrpc.newBlockingStub(channel)
                    .withMaxInboundMessageSize(MAX_MESSAGE_BYTES)
                    .withMaxOutboundMessageSize(MAX_MESSAGE_BYTES)
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
        return call(to, FAST_TIMEOUT_MS,
                s -> s.election(ElectionMsg.newBuilder().setHeader(header()).build()),
                ElectionReply::getHeader)
                .map(ElectionReply::getOk)
                .orElse(false);
    }

    @Override
    public void broadcastCoordinator() {
        for (int id : stubs.keySet()) {
            executor.submit(() -> ack(id, FAST_TIMEOUT_MS, s -> s.coordinator(
                    CoordinatorMsg.newBuilder().setHeader(header()).setLeaderId(selfId).build())));
        }
    }

    @Override
    public boolean heartbeat(int to) {
        // Timeout maior que o dos demais RPCs: um nó ocupado (rodada de ACO) não pode ser
        // confundido com líder morto só porque demorou mais de 500 ms para responder.
        return ack(to, HEARTBEAT_TIMEOUT_MS, s -> s.heartbeat(HeartbeatMsg.newBuilder().setHeader(header()).build()));
    }

    // ---------------------------------------------------------------- Exclusão mútua

    @Override
    public Set<Integer> alivePeers() {
        return Set.copyOf(alive);
    }

    @Override
    public boolean sendRequest(int to, long timestamp) {
        // Peer em quarentena: nem tenta, para o acquire() não pagar 500 ms por rodada.
        if (!shouldContact(to)) return false;
        return ack(to, FAST_TIMEOUT_MS, s -> s.requestCS(
                CsRequest.newBuilder().setHeader(header()).setRequestTs(timestamp).build()));
    }

    @Override
    public void sendReply(int to, long requestTs) {
        try {
            executor.submit(() -> ack(to, FAST_TIMEOUT_MS,
                    s -> s.replyCS(CsReply.newBuilder().setHeader(header()).setRequestTs(requestTs).build())));
        } catch (RejectedExecutionException ignored) {
            // nó sendo desligado: não há mais quem esperar por este REPLY
        }
    }

    // ---------------------------------------------------------------- Rodadas ACO

    public void broadcastStartRound(int round) {
        for (int id : stubs.keySet()) {
            if (!shouldContact(id)) continue;
            executor.submit(() -> ack(id, FAST_TIMEOUT_MS, s -> s.startRound(
                    RoundStart.newBuilder().setHeader(header()).setRound(round).build())));
        }
    }

    public void sendRoundDone(int leader, int round, Tour tour) {
        ack(leader, FAST_TIMEOUT_MS, s -> s.roundDone(RoundResult.newBuilder()
                .setHeader(header())
                .setRound(round)
                .addAllTour(Protos.boxed(tour.cities()))
                .setLength(tour.length())
                .build()));
    }

    /** Envia a matriz a todos os peers e espera as confirmações (chamado dentro da seção crítica). */
    public void broadcastPheromone(int round, double[] tau, Tour best, int coordinator) {
        PheromoneUpdate base = PheromoneUpdate.newBuilder()
                .setRound(round)
                .addAllTau(Protos.boxed(tau))
                .addAllBestTour(Protos.boxed(best.cities()))
                .setBestLength(best.length())
                .build();
        List<Future<Boolean>> pending = stubs.keySet().stream()
                // Peer morto não pode custar 2 s dentro da seção crítica em toda rodada. O
                // coordenador é a exceção: ele é quem precisa da matriz para a próxima rodada,
                // então vale a pena tentar mesmo se estiver em quarentena.
                .filter(id -> id == coordinator || shouldContact(id))
                .map(id -> executor.submit(() -> ack(id, BROADCAST_TIMEOUT_MS,
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
            retryAfter.remove(peer);
            if (alive.add(peer)) Log.info("N%d voltou a responder", peer);
            return Optional.of(response);
        } catch (StatusRuntimeException e) {
            boolean wasAlive = alive.remove(peer);
            retryAfter.put(peer, System.currentTimeMillis() + RETRY_AFTER_MS);
            if (wasAlive) {
                Status.Code code = e.getStatus().getCode();
                if (code == Status.Code.UNAUTHENTICATED) {
                    Log.info("N%d recusou o token do cluster (UNAUTHENTICATED): confira CLUSTER_TOKEN", peer);
                } else if (code == Status.Code.PERMISSION_DENIED) {
                    Log.info("N%d não reconhece este nó (PERMISSION_DENIED): confira PEERS", peer);
                } else {
                    Log.info("N%d inacessível (%s)", peer, code);
                }
            }
            return Optional.empty();
        }
    }

    /** false enquanto o peer está em quarentena; depois disso, uma nova tentativa vale a pena. */
    private boolean shouldContact(int peer) {
        if (alive.contains(peer)) return true;
        Long next = retryAfter.get(peer);
        return next == null || System.currentTimeMillis() >= next;
    }

    @Override
    public void close() {
        executor.shutdownNow();
        channels.values().forEach(ManagedChannel::shutdownNow);
    }
}
