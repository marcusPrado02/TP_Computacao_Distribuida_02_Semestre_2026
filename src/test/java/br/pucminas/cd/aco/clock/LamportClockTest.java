package br.pucminas.cd.aco.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LamportClockTest {

    @Test
    void tickIncrementa() {
        LamportClock clock = new LamportClock();
        assertEquals(1, clock.tick());
        assertEquals(2, clock.tick());
    }

    @Test
    void recebimentoUsaMaximoMaisUm() {
        LamportClock clock = new LamportClock();
        clock.tick(); // 1
        assertEquals(11, clock.onReceive(10));
        assertEquals(12, clock.onReceive(3)); // remoto atrasado não volta o relógio
    }
}
