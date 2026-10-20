package br.pucminas.cd.aco;

import br.pucminas.cd.aco.config.NodeConfig;
import java.io.IOException;

/** Ponto de entrada de um nó. Todos os nós rodam este mesmo programa; ver {@link Node}. */
public final class NodeMain {
    private NodeMain() {}

    public static void main(String[] args) throws Exception {
        NodeConfig cfg;
        try {
            cfg = NodeConfig.fromEnv();
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Configuração inválida: " + e.getMessage());
            System.exit(2);
            return;
        }

        Node node;
        try {
            node = new Node(cfg);
        } catch (IOException e) {
            System.err.println("Falha ao carregar a instância TSP: " + e.getMessage());
            System.exit(2);
            return;
        } catch (IllegalArgumentException e) {
            System.err.println("Instância TSP inválida: " + e.getMessage());
            System.exit(2);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                node.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "shutdown"));
        node.start();
        node.awaitTermination();
    }
}
