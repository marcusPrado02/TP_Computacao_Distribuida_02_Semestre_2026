package br.pucminas.cd.aco.clock;

/**
 * Relógio lógico de Lamport.
 * Regras: evento local/envio -> tick(); recebimento -> max(local, remoto) + 1.
 */
public final class LamportClock {
    private long time;

    /** Evento local ou envio de mensagem. */
    public synchronized long tick() {
        return ++time;
    }

    /** Recebimento de mensagem carimbada com {@code remote}. */
    public synchronized long onReceive(long remote) {
        time = Math.max(time, remote) + 1;
        return time;
    }

    public synchronized long now() {
        return time;
    }
}
