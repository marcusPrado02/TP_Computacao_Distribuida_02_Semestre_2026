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
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Lado cliente do gRPC: canais para cada peer, carimbo de Lamport em toda mensagem
 * e visão de quais peers estão vivos (atualizada a cada chamada).
 */
public final class PeerNetwork implements MutexTransport, ElectionTransport, AutoCloseable {
    /** Padrões para loopback/compose; em LAN o nó usa os valores de {@code NodeConfig.Timeouts}. */
    static final long DEFAULT_FAST = 500;
    static final long DEFAULT_BROADCAST = 2_000;
    /** Piso do timeout de heartbeat: um nó ocupado não pode ser confundido com líder morto. */
    private static final long HEARTBEAT_MIN_TIMEOUT_MS = 1_000;
    /** Intervalo mínimo antes de tentar de novo um peer que falhou (evita 500 ms por rodada). */
    private static final long RETRY_AFTER_MS = 2_000;

    /** Resultado do handshake de configuração (heartbeat usado como probe no startup). */
    public enum HandshakeResult { OK, UNREACHABLE, AUTH, PEERS, OTHER }

    /** Limite de mensagem gRPC. O padrão (4 MiB) estoura a matriz a partir de ~725 cidades. */
    public static final int MAX_MESSAGE_BYTES = 64 * 1024 * 1024;

    private final int selfId;
    private final LamportClock clock;
    private final long fastTimeoutMs;
    private final long broadcastTimeoutMs;
    /** Nunca menor que {@link #HEARTBEAT_MIN_TIMEOUT_MS}: em LAN acompanha o timeout de RPC. */
    private final long heartbeatTimeoutMs;
    private final Map<Integer, ManagedChannel> channels = new HashMap<>();
    private final Map<Integer, NodeServiceBlockingStub> stubs = new HashMap<>();
    private final Set<Integer> alive = ConcurrentHashMap.newKeySet();
    /** Instante a partir do qual vale a pena tentar de novo um peer marcado como inacessível. */
    private final Map<Integer, Long> retryAfter = new ConcurrentHashMap<>();
    /** Config divergente é erro fatal; o Node instala o handler e encerra o processo. */
    private volatile Consumer<String> configErrorHandler = message -> {};
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
        this.heartbeatTimeoutMs = Math.max(HEARTBEAT_MIN_TIMEOUT_MS, fastTimeoutMs);
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

    /** Conjunto de membros deste nó (self + PEERS), ordenado; usado no handshake. */
    public SortedSet<Integer> members() {
        SortedSet<Integer> all = new TreeSet<>();
        all.add(selfId);
        all.addAll(stubs.keySet());
        return all;
    }

    /** Instala a ação de erro fatal de configuração (usada pelo NodeMain via Node). */
    public void setConfigErrorHandler(Consumer<String> handler) {
        this.configErrorHandler = handler;
    }

    /**
     * Handshake de startup: usa o heartbeat como probe e classifica a falha.
     * Não lança: o chamador decide se {@link HandshakeResult#AUTH}/{@link HandshakeResult#PEERS} é fatal.
     */
    public HandshakeResult probe(int peer) {
        try {
            Ack response = stubs.get(peer)
                    .withDeadlineAfter(heartbeatTimeoutMs, TimeUnit.MILLISECONDS)
                    .heartbeat(HeartbeatMsg.newBuilder()
                            .setHeader(header())
                            .addAllMembers(members())
                            .build());
            clock.onReceive(response.getHeader().getLamport());
            retryAfter.remove(peer);
            alive.add(peer);
            return HandshakeResult.OK;
        } catch (StatusRuntimeException e) {
            alive.remove(peer);
            retryAfter.put(peer, System.currentTimeMillis() + RETRY_AFTER_MS);
            return switch (e.getStatus().getCode()) {
                case UNAUTHENTICATED -> HandshakeResult.AUTH;
                case PERMISSION_DENIED, FAILED_PRECONDITION -> HandshakeResult.PEERS;
                case UNAVAILABLE, DEADLINE_EXCEEDED -> HandshakeResult.UNREACHABLE;
                default -> HandshakeResult.OTHER;
            };
        }
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
        // Timeout maior que o dos demais RPCs: um nó ocupado (rodada de ACO) não pode ser
        // confundido com líder morto só porque demorou mais de 500 ms para responder.
        return ack(to, heartbeatTimeoutMs, s -> s.heartbeat(HeartbeatMsg.newBuilder().setHeader(header()).build()));
    }

    // ---------------------------------------------------------------- Exclusão mútua

    @Override
    public Set<Integer> alivePeers() {
        return Set.copyOf(alive);
    }

    @Override
    public boolean sendRequest(int to, long timestamp) {
        // Peer em quarentena: nem tenta, para o acquire() não pagar o timeout por rodada.
        if (!shouldContact(to)) return false;
        return ack(to, fastTimeoutMs, s -> s.requestCS(
                CsRequest.newBuilder().setHeader(header()).setRequestTs(timestamp).build()));
    }

    @Override
    public void sendReply(int to, long requestTs) {
        try {
            executor.submit(() -> ack(to, fastTimeoutMs,
                    s -> s.replyCS(CsReply.newBuilder().setHeader(header()).setRequestTs(requestTs).build())));
        } catch (RejectedExecutionException ignored) {
            // nó sendo desligado: não há mais quem esperar por este REPLY
        }
    }

    // ---------------------------------------------------------------- Rodadas ACO

    public void broadcastStartRound(int round) {
        for (int id : stubs.keySet()) {
            if (!shouldContact(id)) continue;
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
    public void broadcastPheromone(int round, double[] tau, Tour best, int coordinator) {
        PheromoneUpdate base = PheromoneUpdate.newBuilder()
                .setRound(round)
                .addAllTau(Protos.boxed(tau))
                .addAllBestTour(Protos.boxed(best.cities()))
                .setBestLength(best.length())
                .build();
        List<Future<Boolean>> pending = stubs.keySet().stream()
                // Peer morto não pode custar o broadcast inteiro dentro da seção crítica em toda
                // rodada. O coordenador é a exceção: ele precisa da matriz para a próxima rodada,
                // então vale a pena tentar mesmo se estiver em quarentena.
                .filter(id -> id == coordinator || shouldContact(id))
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
                    configErrorHandler.accept("N" + peer + " recusou o CLUSTER_TOKEN (UNAUTHENTICATED)");
                } else if (code == Status.Code.PERMISSION_DENIED) {
                    Log.info("N%d não reconhece este nó (PERMISSION_DENIED): confira PEERS", peer);
                    configErrorHandler.accept("N" + peer + " não reconhece este nó (PERMISSION_DENIED): confira PEERS");
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
