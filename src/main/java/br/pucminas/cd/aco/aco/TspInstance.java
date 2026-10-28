package br.pucminas.cd.aco.aco;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Instância do TSP simétrico euclidiano (distâncias arredondadas como no TSPLIB EUC_2D).
 *
 * <p>Guarda a matriz de distâncias completa (n×n), calculada uma vez. Imutável e, portanto,
 * segura para leitura concorrente pelas formigas. Todos os nós precisam construir a mesma
 * instância (mesma semente ou mesmo arquivo), senão os índices das cidades não batem.
 */
public final class TspInstance {
    private final double[][] dist;

    private TspInstance(double[][] dist) {
        this.dist = dist;
    }

    /** Cidades aleatórias em [0, 1000)²; mesma semente => mesma instância em todos os nós. */
    public static TspInstance random(int n, long seed) {
        Random rnd = new Random(seed);
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = rnd.nextDouble() * 1000;
            y[i] = rnd.nextDouble() * 1000;
        }
        return fromCoords(x, y);
    }

    /**
     * Lê arquivo TSPLIB com NODE_COORD_SECTION (ex.: berlin52.tsp).
     * Cada linha da seção é "índice x y"; o índice do arquivo é ignorado e a ordem de leitura vale.
     */
    public static TspInstance fromTsplib(Path file) throws IOException {
        List<double[]> coords = new ArrayList<>();
        boolean inCoords = false;
        int lineNumber = 0;
        for (String line : Files.readAllLines(file)) {
            lineNumber++;
            String trimmed = line.trim();
            if (!inCoords) {
                if (trimmed.equals("NODE_COORD_SECTION")) inCoords = true;
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            // Outra seção (DISPLAY_DATA_SECTION etc.) ou fim do arquivo: para de ler coordenadas.
            if (trimmed.equals("EOF") || trimmed.endsWith("_SECTION")) break;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 3) {
                throw new IOException(file + ":" + lineNumber
                        + ": esperado 'id x y', veio '" + trimmed + "'");
            }
            try {
                coords.add(new double[] {Double.parseDouble(parts[1]), Double.parseDouble(parts[2])});
            } catch (NumberFormatException e) {
                throw new IOException(file + ":" + lineNumber
                        + ": coordenada inválida em '" + trimmed + "'", e);
            }
        }
        if (!inCoords) {
            throw new IOException(file + ": seção NODE_COORD_SECTION ausente");
        }
        if (coords.size() < 3) {
            throw new IOException(file + ": " + coords.size()
                    + " cidade(s) em NODE_COORD_SECTION; o TSP precisa de pelo menos 3");
        }
        double[] x = coords.stream().mapToDouble(c -> c[0]).toArray();
        double[] y = coords.stream().mapToDouble(c -> c[1]).toArray();
        return fromCoords(x, y);
    }

    /** Constrói a matriz de distâncias a partir das coordenadas (x[i], y[i]). */
    public static TspInstance fromCoords(double[] x, double[] y) {
        if (x.length != y.length) {
            throw new IllegalArgumentException("x e y com tamanhos diferentes: " + x.length + " e " + y.length);
        }
        int n = x.length;
        if (n < 3) {
            throw new IllegalArgumentException("instância com " + n + " cidade(s); o TSP precisa de pelo menos 3");
        }
        double[][] dist = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                // EUC_2D do TSPLIB: distância euclidiana arredondada para inteiro (comparável à literatura)
                dist[i][j] = Math.rint(Math.hypot(x[i] - x[j], y[i] - y[j]));
            }
        }
        return new TspInstance(dist);
    }

    /** Número de cidades. */
    public int size() {
        return dist.length;
    }

    /** Distância entre as cidades i e j. */
    public double distance(int i, int j) {
        return dist[i][j];
    }

    /** Comprimento da rota fechada que visita {@code path} em ordem e volta ao início. */
    public double tourLength(int[] path) {
        double total = 0;
        for (int k = 0; k < path.length; k++) {
            total += dist[path[k]][path[(k + 1) % path.length]];
        }
        return total;
    }

    /** Heurística do vizinho mais próximo: usada para τ0 e como linha de base no relatório. */
    public double nearestNeighborLength() {
        int n = size();
        boolean[] visited = new boolean[n];
        int[] path = new int[n];
        visited[0] = true;
        for (int step = 1; step < n; step++) {
            int from = path[step - 1];
            int best = -1;
            for (int j = 0; j < n; j++) {
                if (!visited[j] && (best < 0 || dist[from][j] < dist[from][best])) best = j;
            }
            path[step] = best;
            visited[best] = true;
        }
        return tourLength(path);
    }
}
