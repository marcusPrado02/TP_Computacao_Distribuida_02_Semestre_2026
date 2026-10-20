package br.pucminas.cd.aco.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.pucminas.cd.aco.Node;
import br.pucminas.cd.aco.clock.LamportClock;
import br.pucminas.cd.aco.config.NodeConfig;
import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HandshakeTest {

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void classificaTokenEPeersDoCluster() throws Exception {
        int port = freePort();
        // N1 conhece N2 e N3; os endereços não precisam existir para este teste.
        NodeConfig cfg = new NodeConfig(1, port, Map.of(2, "localhost:1", 3, "localhost:1"),
                "token-A", null, 5, 42, 1, 1, 1.0, 3.0, 0.1, 0);
        Node node = new Node(cfg);
        node.start();
        try (PeerNetwork wrongToken = new PeerNetwork(3, new LamportClock(),
                     Map.of(1, "localhost:" + port, 2, "localhost:1"), "token-B");
             PeerNetwork wrongPeers = new PeerNetwork(4, new LamportClock(),
                     Map.of(1, "localhost:" + port), "token-A");
             PeerNetwork wrongMembers = new PeerNetwork(3, new LamportClock(),
                     Map.of(1, "localhost:" + port), "token-A");
             PeerNetwork right = new PeerNetwork(3, new LamportClock(),
                     Map.of(1, "localhost:" + port, 2, "localhost:1"), "token-A")) {

            assertEquals(PeerNetwork.HandshakeResult.AUTH, wrongToken.probe(1),
                    "token diferente deve ser UNAUTHENTICATED");
            assertEquals(PeerNetwork.HandshakeResult.PEERS, wrongPeers.probe(1),
                    "remetente fora de PEERS deve ser PERMISSION_DENIED");
            assertEquals(PeerNetwork.HandshakeResult.PEERS, wrongMembers.probe(1),
                    "conjunto de membros diferente deve ser recusado");
            assertEquals(PeerNetwork.HandshakeResult.OK, right.probe(1),
                    "token e PEERS corretos devem responder OK");
        } finally {
            node.close();
        }
    }
}
