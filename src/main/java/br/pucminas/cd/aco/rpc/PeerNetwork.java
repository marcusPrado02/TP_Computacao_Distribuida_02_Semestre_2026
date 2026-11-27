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
import br.pucminas.cd.aco.grpc.StateRequest;
import br.pucminas.cd.aco.grpc.StateSnapshot;
import br.pucminas.cd.aco.grpc.UpdateReply;
import br.pucminas.cd.aco.mutex.MutexTransport;
import br.pucminas.cd.aco.replica.ReplicaSnapshot;
import br.pucminas.cd.aco.replica.Version;
import br.pucminas.cd.aco.util.Log;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
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
 *
 * <p>Detector de falhas implícito: toda chamada tem deadline. Sucesso marca o peer como vivo;
 * erro (timeout, conexão recusada) o marca como inacessível. Esse conjunto ({@link #alivePeers})
 * alimenta o Ricart-Agrawala e o coordenador de rodadas.
 *
 * <p>Thread-safety: canais e stubs são criados no construtor e só lidos depois (stubs gRPC são
 * thread-safe); {@code alive} é um conjunto concorrente. Envios em broadcast e assíncronos usam
 * virtual threads, baratas o bastante para uma por chamada.
 */
public final class PeerNetwork implements MutexTransport, ElectionTransport, AutoCloseable {
    /**
     * Padrões para loopback/compose; em LAN o nó usa os valores de {@code NodeConfig.Timeouts}.
     * Deadline das mensagens de controle (eleição, heartbeat, mutex, rodadas).
     */
    static final long DEFAULT_FAST = 500;
    /** Deadline do envio da matriz de feromônio (n² doubles, mensagem bem maior). */
    static final long DEFAULT_BROADCAST = 2_000;
    /** Piso do timeout de heartbeat: um nó ocupado não pode ser confundido com líder morto. */
    private static final long HEARTBEAT_MIN_TIMEOUT_MS = 1_000;
    /** Intervalo mínimo antes de tentar de novo um peer que falhou (evita 500 ms por rodada). */
    private static final long RETRY_AFTER_MS = 2_000;
    /** Tentativas do RoundDone: perder um só faria o líder esperar o timeout da rodada (30 s). */
    private static final int ROUND_DONE_ATTEMPTS = 3;
    private static final long ROUND_DONE_BACKOFF_MS = 200;
    /** Piso da janela de suspeita; o valor efetivo é {@code max(1,5 s, 3 × RPC_TIMEOUT_MS)}. */
    private static final long SUSPECT_MIN_MS = 1_500;

    /** Resultado do handshake de configuração (heartbeat usado como probe no startup). */
    public enum HandshakeResult { OK, UNREACHABLE, AUTH, PEERS, OTHER }

    /** Primeira e última tentativa que falhou desde o último contato bem-sucedido, em ms. */
    private record FailureStreak(long since, long last) {}

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
    /**
     * Sequência atual de falhas de cada peer; some no primeiro contato bem-sucedido (chamada de
     * saída ou mensagem recebida).
     */
    private final Map<Integer, FailureStreak> failing = new ConcurrentHashMap<>();
    /** Falhando há mais que isto, sem contato no meio = suspeito de ter caído (ver {@link #isSuspected}). */
    private final long suspectAfterMs;
    /** Config divergente é erro fatal; o Node instala o handler e encerra o processo. */
    private volatile Consumer<String> configErrorHandler = message -> {};
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Abre um canal (sem TLS) para cada peer; todos começam marcados como vivos.
     * Os canais conectam sob demanda, então o peer não precisa estar no ar ainda.
     */
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
        this.suspectAfterMs = Math.max(SUSPECT_MIN_MS, 3 * fastTimeoutMs);
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
            failing.remove(peer);
            retryAfter.remove(peer);
            alive.add(peer);
            return HandshakeResult.OK;
        } catch (StatusRuntimeException e) {
            failed(peer);
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

    /** Retrato imutável dos peers vivos neste momento. */
    @Override
    public Set<Integer> alivePeers() {
        return Set.copyOf(alive);
    }

    @Override
    public boolean sendRequest(int to, long timestamp) {
        // Peer em quarentena e já suspeito: nem tenta, para o acquire() não pagar o timeout por
        // rodada. Em quarentena mas ainda não suspeito: tenta, porque pode estar vivo (e o REPLY
        // dele é necessário para a exclusão mútua).
        if (!shouldContact(to) && isSuspected(to)) return false;
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

    /**
     * Líder avisa todos os peers do início da rodada (assíncrono, não espera os acks).
     * {@code version} é a versão da réplica do líder: quem estiver atrás faz catch-up antes.
     */
    public void broadcastStartRound(int round, long version) {
        for (int id : stubs.keySet()) {
            if (!shouldContact(id)) continue;
            executor.submit(() -> ack(id, fastTimeoutMs, s -> s.startRound(
                    RoundStart.newBuilder().setHeader(header()).setRound(round).setVersion(version).build())));
        }
    }

    /**
     * Informa ao líder a melhor rota local da rodada (síncrono), com até 3 tentativas.
     * Uma perda isolada (pacote, pico de carga) não pode custar ao líder os 30 s do timeout da
     * rodada; para de tentar se o líder for dado como morto (aí quem assume refaz a rodada).
     *
     * @return true se o líder confirmou
     */
    public boolean sendRoundDone(int leader, int round, Tour tour) {
        RoundResult base = RoundResult.newBuilder()
                .setRound(round)
                .addAllTour(Protos.boxed(tour.cities()))
                .setLength(tour.length())
                .build();
        for (int attempt = 1; attempt <= ROUND_DONE_ATTEMPTS; attempt++) {
            if (ack(leader, fastTimeoutMs, s -> s.roundDone(base.toBuilder().setHeader(header()).build()))) {
                return true;
            }
            if (attempt < ROUND_DONE_ATTEMPTS) {
                try {
                    Thread.sleep(ROUND_DONE_BACKOFF_MS * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        Log.info("RoundDone da rodada %d não chegou a N%d após %d tentativas", round, leader, ROUND_DONE_ATTEMPTS);
        return false;
    }

    // ---------------------------------------------------------------- Réplicas / recuperação

    /** Pede o estado completo a um peer (FetchState). Vazio se ele não respondeu. */
    public Optional<ReplicaSnapshot> fetchState(int peer) {
        if (!stubs.containsKey(peer) || !shouldContact(peer)) return Optional.empty();
        return call(peer, broadcastTimeoutMs,
                s -> s.fetchState(StateRequest.newBuilder().setHeader(header()).build()),
                StateSnapshot::getHeader)
                .map(Protos::snapshot);
    }

    /** FetchState em paralelo em todos os peers contatáveis; devolve os que responderam. */
    public List<ReplicaSnapshot> fetchStateFromAll() {
        List<Future<Optional<ReplicaSnapshot>>> futures = new ArrayList<>();
        for (int id : stubs.keySet()) futures.add(executor.submit(() -> fetchState(id)));
        List<ReplicaSnapshot> result = new ArrayList<>();
        for (Future<Optional<ReplicaSnapshot>> f : futures) {
            try {
                f.get().ifPresent(result::add);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                Log.info("Falha no FetchState: %s", e.getCause());
            }
        }
        return result;
    }

    /**
     * Envia a matriz a todos os peers e espera as confirmações (chamado dentro da seção crítica).
     *
     * <p>Esperar os acks antes de sair da seção crítica garante que o próximo nó a entrar já tem
     * a matriz atualizada. Os envios são paralelos, então o tempo é o do peer vivo mais lento.
     *
     * <p>Só se esperam os peers vivos. Peer já marcado como inacessível recebe o envio em segundo
     * plano, fora da espera: se morreu, não segura a SC pelo timeout inteiro a cada fim de
     * quarentena; se voltou, o próprio envio o marca como vivo e ele volta a ser esperado. Em
     * quarentena ele nem é tentado, exceto o coordenador da rodada, que precisa da matriz e por
     * isso é sempre tentado (mas, morto, também não é esperado: líder que cai no meio da rodada
     * faria cada nó da fila ficar o timeout inteiro na SC, atrasando a 1ª rodada do novo líder).
     *
     * <p>Cada réplica responde se aplicou e com qual versão ficou. Se alguma tinha versão
     * <i>mais nova</i> que a enviada, quem escreveu estava atrasado (ou houve escrita concorrente):
     * o chamador usa o retorno para fazer catch-up daquela réplica.
     *
     * @return id do peer com a réplica mais nova que {@code version}, ou -1 se nenhum
     */
    public int broadcastPheromone(int round, double[] tau, Version version, Tour best, int coordinator) {
        PheromoneUpdate base = PheromoneUpdate.newBuilder()
                .setRound(round)
                .addAllTau(Protos.boxed(tau))
                .addAllBestTour(Protos.boxed(best.cities()))
                .setBestLength(best.length())
                .setVersion(version.number())
                .setWriterId(version.writer())
                .build();
        Map<Integer, Future<Optional<UpdateReply>>> pending = new HashMap<>();
        for (int id : stubs.keySet()) {
            boolean mustWait = alive.contains(id);
            if (!mustWait && id != coordinator && !shouldContact(id)) continue;
            Future<Optional<UpdateReply>> sent = executor.submit(() -> call(id, broadcastTimeoutMs,
                    s -> s.updatePheromone(base.toBuilder().setHeader(header()).build()),
                    UpdateReply::getHeader));
            if (mustWait) pending.put(id, sent);
        }
        int newerPeer = -1;
        Version newest = version;
        for (var entry : pending.entrySet()) {
            try {
                Optional<UpdateReply> reply = entry.getValue().get();
                if (reply.isEmpty() || reply.get().getApplied()) continue;
                Version theirs = new Version(reply.get().getVersion(), reply.get().getWriterId());
                if (theirs.isNewerThan(newest)) {
                    newest = theirs;
                    newerPeer = entry.getKey();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return newerPeer;
            } catch (ExecutionException e) {
                Log.info("Falha no broadcast de feromônio: %s", e.getCause());
            }
        }
        return newerPeer;
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
            failing.remove(peer);
            retryAfter.remove(peer);
            if (alive.add(peer)) Log.info("N%d voltou a responder", peer);
            return Optional.of(response);
        } catch (StatusRuntimeException e) {
            failed(peer);
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

    /**
     * Mensagem recebida de {@code peer} prova que ele está vivo: sai da quarentena na hora.
     *
     * <p>Sem isso, só as chamadas <i>de saída</i> atualizavam a visão. Um peer que ainda estava subindo
     * quando foi testado ficava 2 s em quarentena mesmo já falando conosco, e o Ricart-Agrawala o
     * contava como permissão: dois nós na seção crítica logo na 1ª rodada (o carimbo de versão
     * da réplica revelou isso como conflito em v1).
     */
    public void markAlive(int peer) {
        if (!stubs.containsKey(peer)) return;
        failing.remove(peer);
        retryAfter.remove(peer);
        if (alive.add(peer)) {
            Log.info("N%d voltou a responder", peer);
            // O canal pode estar em reconnect backoff desde a última falha: reconecta já.
            channels.get(peer).resetConnectBackoff();
        }
    }

    /**
     * Detector de falhas com janela: o peer é <b>suspeito</b> só quando as tentativas de contato com
     * ele <i>falham</i> há mais de {@code suspectAfterMs}, sem nenhum contato bem-sucedido (de ida ou
     * de volta) entre a primeira e a última falha.
     *
     * <p>Uma falha isolada não basta. O caso que motivou isto: na subida, o canal gRPC para um peer
     * que ainda não estava ouvindo entra em <i>reconnect backoff</i> (~1 s), e toda chamada nesse
     * intervalo falha na hora com UNAVAILABLE — com o peer já vivo. Com "falha = morto", o
     * Ricart-Agrawala contava isso como permissão e dois nós entravam juntos na seção crítica.
     *
     * <p>Silêncio também não basta. Dois seguidores só se falam na fase da seção crítica; numa
     * rodada em que as formigas levam mais que a janela, eles passam a janela inteira sem contato,
     * os dois vivos. Por isso a janela é medida entre <i>falhas</i>, e não desde o último contato.
     */
    @Override
    public boolean isSuspected(int peer) {
        FailureStreak streak = failing.get(peer);
        return streak != null && streak.last() - streak.since() > suspectAfterMs;
    }

    /** Registra uma tentativa de contato que falhou: começa ou estende a sequência de falhas. */
    private void failed(int peer) {
        long now = System.currentTimeMillis();
        failing.merge(peer, new FailureStreak(now, now), (old, fresh) -> new FailureStreak(old.since(), now));
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
