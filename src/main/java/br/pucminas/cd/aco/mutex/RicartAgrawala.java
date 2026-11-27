package br.pucminas.cd.aco.mutex;

import br.pucminas.cd.aco.clock.LamportClock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exclusão mútua distribuída de Ricart-Agrawala.
 *
 * <p>Seção crítica do sistema: atualizar a matriz de feromônio global e propagá-la aos peers.
 * Para entrar, o nó envia REQUEST(ts) a todos e espera REPLY de cada um. Quem recebe um REQUEST
 * responde na hora ou adia a resposta até sair da própria seção crítica. Os conflitos são
 * resolvidos pelo par (timestamp de Lamport, id do nó): o menor tem prioridade.
 *
 * <p>Cada REPLY carrega o timestamp do pedido que autoriza; REPLYs de pedidos antigos são descartados.
 *
 * <p>Tolerância a falhas: um peer só conta como permissão quando um REQUEST a ele falha <b>e</b> o
 * detector de falhas já o <b>suspeita</b> ({@link MutexTransport#isSuspected}) — tentativas de contato
 * falhando há uma janela inteira, não apenas um RPC que falhou nem um período sem contato. Enquanto
 * não é suspeito, o pedido é reenviado a cada {@link #LIVENESS_POLL_MS}, o que recupera REPLYs
 * perdidos e espera um peer que só estava momentaneamente inacessível (canal gRPC em reconnect
 * backoff, pico de carga). Na Entrega 1, uma falha isolada já valia como permissão, e isso colocava
 * dois nós na seção crítica. A exclusão ainda pode falhar se o peer ficar sem responder a janela
 * inteira e continuar vivo; nesse caso o carimbo de versão da réplica detecta o conflito e as
 * réplicas convergem (ver PheromoneMatrix).
 *
 * <p>Custo: 2(N − 1) mensagens por entrada na seção crítica (N − 1 REQUESTs e N − 1 REPLYs).
 *
 * <p>Thread-safety: todo o estado é protegido pelo monitor de {@code this}. {@link #acquire} roda na
 * thread do trabalhador e espera com {@code wait}; {@link #onRequest} e {@link #onReply} são chamados
 * pelos handlers gRPC e acordam a espera com {@code notifyAll}. Os REQUESTs saem por virtual threads
 * para não bloquear segurando o lock.
 */
public final class RicartAgrawala implements AutoCloseable {
    /** RELEASED: fora e sem interesse; WANTED: pediu e espera REPLYs; HELD: dentro da seção crítica. */
    enum State { RELEASED, WANTED, HELD }

    /** Intervalo para reavaliar quem ainda está vivo enquanto espera os REPLYs. */
    private static final long LIVENESS_POLL_MS = 500;

    private final int selfId;
    private final LamportClock clock;
    private final MutexTransport transport;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // Protegidos por this
    private State state = State.RELEASED;
    private long requestTs;
    private final Set<Integer> awaiting = new HashSet<>();
    /** Pedidos adiados: id do solicitante -&gt; timestamp do pedido dele. */
    private final Map<Integer, Long> deferred = new HashMap<>();

    public RicartAgrawala(int selfId, LamportClock clock, MutexTransport transport) {
        this.selfId = selfId;
        this.clock = clock;
        this.transport = transport;
    }

    /**
     * Bloqueia até obter permissão de todos os peers vivos.
     *
     * <p>O timestamp do pedido é um {@code tick()} do relógio de Lamport, fixo durante toda a espera.
     * A espera usa {@code wait} com timeout para reavaliar periodicamente quem ainda está vivo,
     * em vez de depender só de um {@code notifyAll} que pode nunca chegar se o peer morreu.
     *
     * @throws IllegalStateException se chamado de novo antes do {@link #release}
     * @throws InterruptedException  se a thread for interrompida; o pedido é abandonado
     */
    public void acquire() throws InterruptedException {
        long ts;
        Set<Integer> targets;
        synchronized (this) {
            if (state != State.RELEASED) {
                throw new IllegalStateException("acquire() reentrante: estado " + state);
            }
            state = State.WANTED;
            ts = clock.tick();
            requestTs = ts;
            // Todos os peers, não só os "vivos": um peer que foi marcado inacessível numa rodada
            // anterior precisa ser recontatado quando voltar (senão fica excluído para sempre).
            targets = new HashSet<>(transport.allPeers());
            awaiting.clear();
            awaiting.addAll(targets);
        }
        for (int peer : targets) {
            executor.submit(() -> {
                // Falhou e já é suspeito de ter caído: não disputa a seção crítica, conta como
                // permissão. Falhou mas não é suspeito: segue aguardado e o laço reenvia.
                if (!transport.sendRequest(peer, ts) && transport.isSuspected(peer)) onReply(peer, ts);
            });
        }
        synchronized (this) {
            try {
                while (!awaiting.isEmpty()) {
                    wait(LIVENESS_POLL_MS);
                    // Reenvia o pedido a quem ainda não respondeu: recupera um REPLY perdido e,
                    // se o peer morreu depois de receber o REQUEST, a falha do reenvio o remove
                    // quando ele já for suspeito. Ninguém sai da espera sem REPLY ou sem uma
                    // tentativa que falhou: o despertar pode ter vindo do REPLY de outro peer,
                    // com o pedido a este ainda em voo.
                    for (int peer : Set.copyOf(awaiting)) {
                        executor.submit(() -> {
                            if (!transport.sendRequest(peer, ts) && transport.isSuspected(peer)) onReply(peer, ts);
                        });
                    }
                }
            } catch (InterruptedException e) {
                // Desiste do pedido sem ficar em WANTED (que adiaria REPLYs para sempre).
                state = State.RELEASED;
                replyDeferred();
                throw e;
            }
            state = State.HELD;
        }
    }

    /** Sai da seção crítica e responde aos pedidos que foram adiados. */
    public void release() {
        synchronized (this) {
            if (state != State.HELD) {
                throw new IllegalStateException("release() sem estar na seção crítica: estado " + state);
            }
            state = State.RELEASED;
            replyDeferred();
        }
    }

    /** Recebeu REQUEST(ts) do nó {@code from}. O relógio já foi atualizado pela camada RPC. */
    public void onRequest(long ts, int from) {
        boolean defer;
        synchronized (this) {
            defer = shouldDefer(ts, from);
            if (defer) deferred.put(from, ts);
        }
        if (!defer) transport.sendReply(from, ts);
    }

    /** Recebeu REPLY de {@code from} autorizando o pedido {@code ts}. */
    public synchronized void onReply(int from, long ts) {
        if (state != State.WANTED || ts != requestTs) return; // REPLY de pedido antigo
        awaiting.remove(from);
        notifyAll();
    }

    /** Estado atual (usado pelos testes). */
    synchronized State state() {
        return state;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /**
     * Decide se o REQUEST (reqTs, reqId) deve esperar até liberarmos a seção crítica.
     *
     * <ul>
     *   <li>HELD: estou usando o recurso, então adio.</li>
     *   <li>WANTED: adio só se o meu pedido tem prioridade, ou seja, (requestTs, selfId) &lt; (reqTs, reqId).</li>
     *   <li>RELEASED: não disputo, respondo na hora.</li>
     * </ul>
     *
     * <p>A comparação lexicográfica (timestamp, id) é uma ordem total: dois pedidos nunca empatam,
     * então exatamente um dos dois nós adia e não há deadlock.
     */
    private boolean shouldDefer(long reqTs, int reqId) {
        return switch (state) {
            case HELD -> true;
            case WANTED -> requestTs < reqTs || (requestTs == reqTs && selfId < reqId);
            case RELEASED -> false;
        };
    }

    /** Chamado com o lock: envia os REPLYs adiados (o envio é assíncrono, não bloqueia aqui). */
    private void replyDeferred() {
        deferred.forEach(transport::sendReply);
        deferred.clear();
    }
}
