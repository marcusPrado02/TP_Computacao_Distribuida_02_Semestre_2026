package br.pucminas.cd.aco.rpc;

import br.pucminas.cd.aco.aco.Tour;
import java.util.Arrays;
import java.util.List;

/**
 * Conversões entre tipos do domínio e campos repeated do protobuf.
 * O protobuf em Java representa repeated como {@code List} de tipos boxed; o domínio usa arrays primitivos.
 */
final class Protos {
    private Protos() {}

    static List<Integer> boxed(int[] values) {
        return Arrays.stream(values).boxed().toList();
    }

    static List<Double> boxed(double[] values) {
        return Arrays.stream(values).boxed().toList();
    }

    static double[] unboxDoubles(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** Rota a partir dos campos da mensagem, ou null se a lista veio vazia (campo ausente). */
    static Tour tour(List<Integer> cities, double length) {
        if (cities.isEmpty()) return null;
        return new Tour(cities.stream().mapToInt(Integer::intValue).toArray(), length);
    }
}
