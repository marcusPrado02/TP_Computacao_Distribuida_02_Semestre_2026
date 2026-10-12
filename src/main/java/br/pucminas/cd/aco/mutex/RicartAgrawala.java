package br.pucminas.cd.aco.mutex;

import br.pucminas.cd.aco.clock.LamportClock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 */
public final class RicartAgrawala {
    enum State { RELEASED, WANTED, HELD }

    private final int selfId;
    private final LamportClock clock;
    private final MutexTransport transport;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // Protegidos por this
    private State state = State.RELEASED;
    private long requestTs;
    private final Set<Integer> awaiting = new HashSet<>();
    private final Set<Integer> deferred = new HashSet<>();

    public RicartAgrawala(int selfId, LamportClock clock, MutexTransport transport) {
        this.selfId = selfId;
        this.clock = clock;
        this.transport = transport;
    }

    /** Bloqueia até obter permissão de todos os peers vivos. */
    public void acquire() throws InterruptedException {
        long ts;
        Set<Integer> targets;
        synchronized (this) {
            state = State.WANTED;
            ts = clock.tick();
            requestTs = ts;
            targets = new HashSet<>(transport.alivePeers());
            awaiting.clear();
            awaiting.addAll(targets);
        }
        for (int peer : targets) {
            executor.submit(() -> {
                // Peer inacessível não disputa a seção crítica: conta como permissão.
                if (!transport.sendRequest(peer, ts)) onReply(peer);
            });
        }
        synchronized (this) {
            while (!awaiting.isEmpty()) wait();
            state = State.HELD;
        }
    }

    /** Sai da seção crítica e responde aos pedidos que foram adiados. */
    public void release() {
        List<Integer> toReply;
        synchronized (this) {
            state = State.RELEASED;
            toReply = new ArrayList<>(deferred);
            deferred.clear();
        }
        toReply.forEach(transport::sendReply);
    }

    /** Recebeu REQUEST(ts) do nó {@code from}. O relógio já foi atualizado pela camada RPC. */
    public void onRequest(long ts, int from) {
        boolean defer;
        synchronized (this) {
            defer = shouldDefer(ts, from);
            if (defer) deferred.add(from);
        }
        if (!defer) transport.sendReply(from);
    }

    /** Recebeu REPLY de {@code from}. */
    public synchronized void onReply(int from) {
        awaiting.remove(from);
        notifyAll();
    }

    synchronized State state() {
        return state;
    }

    /**
     * Decide se o REQUEST (reqTs, reqId) deve esperar até liberarmos a seção crítica.
     * Chamado com o lock de {@code this}; pode ler {@link #state}, {@link #requestTs} e {@link #selfId}.
     *
     * @return true para adiar o REPLY; false para responder imediatamente
     */
    private boolean shouldDefer(long reqTs, int reqId) {
        // TODO(pessoa D): implementar a regra de Ricart-Agrawala (5–8 linhas).
        throw new UnsupportedOperationException("shouldDefer ainda não implementado");
    }
}
