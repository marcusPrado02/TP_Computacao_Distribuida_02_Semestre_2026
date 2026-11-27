package br.pucminas.cd.aco.rpc;

import br.pucminas.cd.aco.aco.Tour;
import br.pucminas.cd.aco.grpc.StateSnapshot;
import br.pucminas.cd.aco.replica.ReplicaSnapshot;
import br.pucminas.cd.aco.replica.Version;
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

    static ReplicaSnapshot snapshot(StateSnapshot msg) {
        return new ReplicaSnapshot(new Version(msg.getVersion(), msg.getWriterId()),
                unboxDoubles(msg.getTauList()), tour(msg.getBestTourList(), msg.getBestLength()),
                msg.getLastRound());
    }

    static StateSnapshot.Builder toMessage(ReplicaSnapshot s) {
        StateSnapshot.Builder b = StateSnapshot.newBuilder()
                .setVersion(s.version().number())
                .setWriterId(s.version().writer())
                .addAllTau(boxed(s.tau()))
                .setLastRound(s.lastRound());
        if (s.best() != null) {
            b.addAllBestTour(boxed(s.best().cities())).setBestLength(s.best().length());
        }
        return b;
    }
}
