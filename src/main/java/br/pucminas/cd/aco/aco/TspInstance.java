package br.pucminas.cd.aco.aco;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Instância do TSP simétrico euclidiano (distâncias arredondadas como no TSPLIB EUC_2D). */
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

    /** Lê arquivo TSPLIB com NODE_COORD_SECTION (ex.: berlin52.tsp). */
    public static TspInstance fromTsplib(Path file) throws IOException {
        List<double[]> coords = new ArrayList<>();
        boolean inCoords = false;
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("NODE_COORD_SECTION")) {
                inCoords = true;
            } else if (trimmed.equals("EOF")) {
                break;
            } else if (inCoords && !trimmed.isEmpty()) {
                String[] parts = trimmed.split("\\s+");
                coords.add(new double[] {Double.parseDouble(parts[1]), Double.parseDouble(parts[2])});
            }
        }
        double[] x = coords.stream().mapToDouble(c -> c[0]).toArray();
        double[] y = coords.stream().mapToDouble(c -> c[1]).toArray();
        return fromCoords(x, y);
    }

    public static TspInstance fromCoords(double[] x, double[] y) {
        int n = x.length;
        double[][] dist = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                dist[i][j] = Math.rint(Math.hypot(x[i] - x[j], y[i] - y[j]));
            }
        }
        return new TspInstance(dist);
    }

    public int size() {
        return dist.length;
    }

    public double distance(int i, int j) {
        return dist[i][j];
    }

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
