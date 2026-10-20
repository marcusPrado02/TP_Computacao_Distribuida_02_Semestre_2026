package br.pucminas.cd.aco.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NodeConfigTest {

    private static NodeConfig config(int id, Map<Integer, String> peers, double rho) {
        return new NodeConfig(id, 50051, peers, "token", null, 10, 42, 5, 4, 1.0, 3.0, rho, 0);
    }

    @Test
    void parsePeersMantemOrdemEIgnoraEspacos() {
        Map<Integer, String> peers = NodeConfig.parsePeers(" 3=node3:50051 , 1=10.0.0.1:50052,");
        assertEquals(List.of(3, 1), List.copyOf(peers.keySet()));
        assertEquals("10.0.0.1:50052", peers.get(1));
    }

    @Test
    void parsePeersRejeitaEntradasInvalidas() {
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parsePeers("2node2:50051"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parsePeers("x=node2:50051"));
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.parsePeers("2=a:1,2=b:2"));
    }

    @Test
    void rejeitaConfiguracaoInconsistente() {
        assertThrows(IllegalArgumentException.class, () -> config(1, Map.of(1, "x:1"), 0.1));
        assertThrows(IllegalArgumentException.class, () -> config(0, Map.of(), 0.1));
        assertThrows(IllegalArgumentException.class, () -> config(1, Map.of(), 0.0));
    }

    @Test
    void tspFileVazioEquivaleAAusente() {
        NodeConfig cfg = new NodeConfig(1, 50051, Map.of(), "token", " ", 10, 42, 5, 4, 1.0, 3.0, 0.1, 0);
        assertNull(cfg.tspFile());
    }
}
