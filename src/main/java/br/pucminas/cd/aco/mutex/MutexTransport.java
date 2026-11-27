package br.pucminas.cd.aco.mutex;

import java.util.Set;

/** O que o Ricart-Agrawala precisa da rede. Implementado por PeerNetwork (gRPC) e por fakes nos testes. */
public interface MutexTransport {
    /** Todos os peers configurados (vivos ou não). Um peer que falhou é re-probado depois. */
    Set<Integer> allPeers();

    /** Peers considerados vivos; consultado no pedido e periodicamente durante a espera. */
    Set<Integer> alivePeers();

    /** Envia REQUEST(ts); false se o peer está inacessível. */
    boolean sendRequest(int to, long timestamp);

    /** Envia REPLY (permissão) ao pedido {@code requestTs} de {@code to}, de forma assíncrona. */
    void sendReply(int to, long requestTs);

    /**
     * true se o detector de falhas já <i>suspeita</i> que o peer caiu — só então ele deixa de ser
     * esperado e conta como permissão. Uma falha isolada de RPC não basta (ver {@code PeerNetwork}).
     * Padrão: suspeito = fora do conjunto de vivos (comportamento da Entrega 1, usado pelos fakes).
     */
    default boolean isSuspected(int peer) {
        return !alivePeers().contains(peer);
    }
}
