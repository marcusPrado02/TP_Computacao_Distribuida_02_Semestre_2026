package br.pucminas.cd.aco.aco;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TspInstanceTest {

    @TempDir Path dir;

    private Path file(String name, String content) throws IOException {
        Path path = dir.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    @Test
    void leCoordenadasEIgnoraOutrasSecoes() throws IOException {
        Path path = file("display3.tsp", """
                NAME: display3
                TYPE: TSP
                DIMENSION: 3
                EDGE_WEIGHT_TYPE: EUC_2D
                NODE_COORD_SECTION
                1 0 0
                2 10 0
                3 0 10
                DISPLAY_DATA_SECTION
                1 0 0
                2 10 0
                3 0 10
                EOF
                """);
        assertEquals(3, TspInstance.fromTsplib(path).size());
    }

    @Test
    void rejeitaArquivoSemSecaoDeCoordenadas() throws IOException {
        Path path = file("sem-coordenadas.tsp", """
                NAME: vazio
                TYPE: TSP
                EOF
                """);
        assertThrows(IOException.class, () -> TspInstance.fromTsplib(path));
    }

    @Test
    void rejeitaMenosDeTresCidades() throws IOException {
        Path path = file("duas.tsp", """
                NAME: duas
                TYPE: TSP
                NODE_COORD_SECTION
                1 0 0
                2 10 0
                EOF
                """);
        assertThrows(IOException.class, () -> TspInstance.fromTsplib(path));
    }

    @Test
    void rejeitaLinhaDeCoordenadaMalFormada() throws IOException {
        Path path = file("quebrado.tsp", """
                NAME: quebrado
                NODE_COORD_SECTION
                1 0
                2 10 0
                3 0 10
                EOF
                """);
        assertThrows(IOException.class, () -> TspInstance.fromTsplib(path));
    }

    @Test
    void fromCoordsValidaTamanhoMinimoEDimensoes() {
        assertThrows(IllegalArgumentException.class,
                () -> TspInstance.fromCoords(new double[] {0, 1}, new double[] {0, 1}));
        assertThrows(IllegalArgumentException.class,
                () -> TspInstance.fromCoords(new double[] {0, 1, 2}, new double[] {0, 1}));
    }

    @Test
    void vizinhoMaisProximoEmQuadrado() {
        TspInstance tsp = TspInstance.fromCoords(
                new double[] {0, 10, 10, 0},
                new double[] {0, 0, 10, 10});
        assertEquals(40.0, tsp.nearestNeighborLength(), 1e-9);
    }
}
