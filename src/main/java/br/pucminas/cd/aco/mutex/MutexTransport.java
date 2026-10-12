package br.pucminas.cd.aco.mutex;

import java.util.Set;

/** O que o Ricart-Agrawala precisa da rede. Implementado por PeerNetwork (gRPC) e por fakes nos testes. */
public interface MutexTransport {
    /** Peers que participam do próximo pedido de seção crítica. */
    Set<Integer> alivePeers();

    /** Envia REQUEST(ts); false se o peer está inacessível. */
    boolean sendRequest(int to, long timestamp);

    /** Envia REPLY (permissão) de forma assíncrona. */
    void sendReply(int to);
}
