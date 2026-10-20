package br.pucminas.cd.aco.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Configuração de um nó, lida de variáveis de ambiente.
 * O mesmo formato serve para Docker Compose e para execução direta na LAN.
 *
 * <p>Os valores são validados na construção: um nó mal configurado falha na subida,
 * com mensagem clara, em vez de se comportar de forma estranha no meio da execução.
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

    public NodeConfig {
        if (tspFile != null && tspFile.isBlank()) tspFile = null; // TSP_FILE="" = não definido
        require(nodeId > 0, "NODE_ID deve ser positivo (o Bully compara ids)");
        require(port > 0 && port <= 65_535, "NODE_PORT fora do intervalo 1–65535: " + port);
        require(!peers.containsKey(nodeId), "PEERS não pode conter o próprio nó (N" + nodeId + ")");
        peers.keySet().forEach(id -> require(id > 0, "id de peer deve ser positivo: " + id));
        require(clusterToken != null && !clusterToken.isBlank(), "CLUSTER_TOKEN obrigatório");
        require(tspFile != null || tspCities >= 3, "TSP_CITIES deve ser >= 3");
        require(rounds >= 1, "ROUNDS deve ser >= 1");
        require(ants >= 1, "ANTS deve ser >= 1");
        require(alpha >= 0 && beta >= 0, "ALPHA e BETA devem ser >= 0");
        require(rho > 0 && rho <= 1, "RHO deve estar em (0, 1]");
        require(startDelayMs >= 0, "START_DELAY_MS deve ser >= 0");
        peers = Collections.unmodifiableMap(new LinkedHashMap<>(peers));
    }

    public static NodeConfig fromEnv() {
        return new NodeConfig(
                parse("NODE_ID", requireEnv("NODE_ID"), Integer::parseInt),
                parse("NODE_PORT", env("NODE_PORT", "50051"), Integer::parseInt),
                parsePeers(env("PEERS", "")),
                requireEnv("CLUSTER_TOKEN"),
                System.getenv("TSP_FILE"),
                parse("TSP_CITIES", env("TSP_CITIES", "60"), Integer::parseInt),
                parse("TSP_SEED", env("TSP_SEED", "42"), Long::parseLong),
                parse("ROUNDS", env("ROUNDS", "30"), Integer::parseInt),
                parse("ANTS", env("ANTS", "40"), Integer::parseInt),
                parse("ALPHA", env("ALPHA", "1.0"), Double::parseDouble),
                parse("BETA", env("BETA", "3.0"), Double::parseDouble),
                parse("RHO", env("RHO", "0.1"), Double::parseDouble),
                parse("START_DELAY_MS", env("START_DELAY_MS", "5000"), Long::parseLong));
    }

    /** Formato: "2=host2:50051,3=host3:50051" (id=host:porta). */
    public static Map<Integer, String> parsePeers(String raw) {
        Map<Integer, String> peers = new LinkedHashMap<>();
        for (String entry : raw.split(",")) {
            if (entry.isBlank()) continue;
            String[] parts = entry.trim().split("=", 2);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw new IllegalArgumentException("PEERS inválido: '" + entry.trim() + "' (esperado id=host:porta)");
            }
            int id = parse("PEERS", parts[0].trim(), Integer::parseInt);
            if (peers.put(id, parts[1].trim()) != null) {
                throw new IllegalArgumentException("PEERS com id repetido: " + id);
            }
        }
        return peers;
    }

    private static <T> T parse(String name, String value, Function<String, T> parser) {
        try {
            return parser.apply(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " com valor inválido: '" + value + "'", e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Variável de ambiente obrigatória ausente: " + name);
        }
        return value;
    }
}
