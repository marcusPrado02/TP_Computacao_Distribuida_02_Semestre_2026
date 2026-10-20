package br.pucminas.cd.aco;

import br.pucminas.cd.aco.config.NodeConfig;

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

        Node node = new Node(cfg);
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
