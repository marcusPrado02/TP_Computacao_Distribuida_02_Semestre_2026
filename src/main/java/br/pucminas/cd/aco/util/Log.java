package br.pucminas.cd.aco.util;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.function.LongSupplier;

/** Log no formato "hora [N<id>][L=<lamport>] mensagem", útil para a demonstração. */
public final class Log {
    private static volatile int nodeId = -1;
    private static volatile LongSupplier lamport = () -> 0;

    private Log() {}

    public static void init(int id, LongSupplier lamportNow) {
        nodeId = id;
        lamport = lamportNow;
    }

    public static void info(String fmt, Object... args) {
        String msg = args.length == 0 ? fmt : String.format(fmt, args);
        System.out.printf("%s [N%d][L=%d] %s%n",
                LocalTime.now().truncatedTo(ChronoUnit.MILLIS), nodeId, lamport.getAsLong(), msg);
    }
}
