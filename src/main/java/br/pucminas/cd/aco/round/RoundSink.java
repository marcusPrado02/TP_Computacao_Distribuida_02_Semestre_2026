package br.pucminas.cd.aco.round;

import br.pucminas.cd.aco.aco.Tour;

/** Destino do aviso "nó terminou a rodada" (o coordenador). */
@FunctionalInterface
public interface RoundSink {
    void onRoundDone(int round, int from, Tour tour);
}
