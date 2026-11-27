package br.pucminas.cd.aco.replica;

/**
 * Carimbo de uma escrita na matriz de feromônio: {@code (number, writer)}.
 *
 * <p>{@code number} cresce de 1 em 1 a cada escrita (versão da réplica sobre a qual se escreveu + 1);
 * {@code writer} é o id do nó que escreveu. A comparação lexicográfica é uma <b>ordem total</b>:
 * duas réplicas que recebem as mesmas escritas, em qualquer ordem, terminam com a mesma
 * (a de maior carimbo) — <i>last-writer-wins</i> determinístico.
 *
 * <p>Mesmo {@code number} com {@code writer} diferente só acontece se duas escritas partiram da
 * mesma versão: ou a exclusão mútua foi violada (peer lento tomado por morto), ou o escritor tinha
 * réplica atrasada. É o sinal que {@code PheromoneMatrix} usa para detectar o conflito.
 *
 * @param number versão (0 = matriz inicial, ninguém escreveu ainda)
 * @param writer id do nó que escreveu (0 na matriz inicial)
 */
public record Version(long number, int writer) implements Comparable<Version> {
    /** Matriz inicial τ0, igual em todos os nós. */
    public static final Version INITIAL = new Version(0, 0);

    public Version {
        if (number < 0) throw new IllegalArgumentException("versão negativa: " + number);
    }

    /** Próxima escrita feita por {@code writer} sobre esta versão. */
    public Version next(int writer) {
        return new Version(number + 1, writer);
    }

    public boolean isNewerThan(Version other) {
        return compareTo(other) > 0;
    }

    /** Mesma versão, escritores diferentes: escritas concorrentes sobre a mesma base. */
    public boolean conflictsWith(Version other) {
        return number == other.number && writer != other.writer;
    }

    @Override
    public int compareTo(Version o) {
        int byNumber = Long.compare(number, o.number);
        return byNumber != 0 ? byNumber : Integer.compare(writer, o.writer);
    }

    @Override
    public String toString() {
        return "v" + number + "/N" + writer;
    }
}
