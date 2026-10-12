package br.pucminas.cd.aco.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuração de um nó, lida de variáveis de ambiente.
 * O mesmo formato serve para Docker Compose e para execução direta na LAN.
 */
public record NodeConfig(
        int nodeId,
        int port,
        Map<Integer, String> peers,
        String clusterToken,
        String tspFile,
        int tspCities,
        long tspSeed,
        int rounds,
        int ants,
        double alpha,
        double beta,
        double rho,
        long startDelayMs) {

    public static NodeConfig fromEnv() {
        return new NodeConfig(
                Integer.parseInt(require("NODE_ID")),
                Integer.parseInt(env("NODE_PORT", "50051")),
                parsePeers(env("PEERS", "")),
                require("CLUSTER_TOKEN"),
                System.getenv("TSP_FILE"),
                Integer.parseInt(env("TSP_CITIES", "60")),
                Long.parseLong(env("TSP_SEED", "42")),
                Integer.parseInt(env("ROUNDS", "30")),
                Integer.parseInt(env("ANTS", "40")),
                Double.parseDouble(env("ALPHA", "1.0")),
                Double.parseDouble(env("BETA", "3.0")),
                Double.parseDouble(env("RHO", "0.1")),
                Long.parseLong(env("START_DELAY_MS", "5000")));
    }

    /** Formato: "2=host2:50051,3=host3:50051" (id=host:porta). */
    public static Map<Integer, String> parsePeers(String raw) {
        Map<Integer, String> peers = new LinkedHashMap<>();
        for (String entry : raw.split(",")) {
            if (entry.isBlank()) continue;
            String[] parts = entry.trim().split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("PEERS inválido: " + entry);
            }
            peers.put(Integer.parseInt(parts[0].trim()), parts[1].trim());
        }
        return peers;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Variável de ambiente obrigatória ausente: " + name);
        }
        return value;
    }
}
