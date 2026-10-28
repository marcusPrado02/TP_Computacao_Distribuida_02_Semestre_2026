package br.pucminas.cd.aco.util;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.function.LongSupplier;

/**
 * Log no formato {@code hora [N<id>][L=<lamport>] mensagem}, útil para a demonstração.
 * Estado global ao processo: assume um nó por JVM (como em produção).
 *
 * <p>O valor de Lamport em cada linha permite intercalar logs de nós diferentes respeitando
 * causalidade: se um evento causou outro, o primeiro tem L menor.
 */
public final class Log {
    private static volatile int nodeId = -1;
    private static volatile LongSupplier lamport = () -> 0;

    private Log() {}

    /** Define o id do nó e de onde ler o relógio de Lamport. */
    public static void init(int id, LongSupplier lamportNow) {
        nodeId = id;
        lamport = lamportNow;
    }

    /** Imprime uma linha; {@code fmt} segue {@link String#format}. */
    public static void info(String fmt, Object... args) {
        String msg = args.length == 0 ? fmt : String.format(fmt, args);
        System.out.printf("%s [N%d][L=%d] %s%n",
                LocalTime.now().truncatedTo(ChronoUnit.MILLIS), nodeId, lamport.getAsLong(), msg);
    }
}
