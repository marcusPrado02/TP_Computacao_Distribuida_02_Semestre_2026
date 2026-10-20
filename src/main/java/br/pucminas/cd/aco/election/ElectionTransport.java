package br.pucminas.cd.aco.election;

import java.util.Set;

/**
 * O que o Bully precisa da rede. Implementado por PeerNetwork (gRPC) e por fakes nos testes.
 *
 * <p>Separar a interface da implementação permite testar o algoritmo sem sockets e mantém
 * o pacote {@code election} independente do gRPC.
 */
public interface ElectionTransport {
    /** Todos os peers configurados (vivos ou não). */
    Set<Integer> allPeers();

    /** Envia ELECTION; true se o peer respondeu OK (está vivo e tem id maior). */
    boolean sendElection(int to);

    /** Anuncia a todos que este nó é o novo coordenador. */
    void broadcastCoordinator();

    /** true se o peer respondeu dentro do timeout. */
    boolean heartbeat(int to);
}
