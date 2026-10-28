package br.pucminas.cd.aco.clock;

/**
 * Relógio lógico de Lamport.
 * Regras: evento local/envio: {@code tick()}; recebimento: {@code max(local, remoto) + 1}.
 *
 * <p>Garantia: se o evento a "aconteceu antes" de b (a → b, mesma thread ou via mensagem),
 * então L(a) &lt; L(b). A recíproca não vale: L(a) &lt; L(b) não implica causalidade.
 * Para ordem total (usada no Ricart-Agrawala), o empate é desfeito pelo id do nó.
 *
 * <p>Thread-safety: os métodos são {@code synchronized}, pois o relógio é compartilhado pelas threads
 * de envio, pelos handlers gRPC e pelo log.
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

    /** Valor atual, sem gerar evento (usado no log). */
    public synchronized long now() {
        return time;
    }
}
