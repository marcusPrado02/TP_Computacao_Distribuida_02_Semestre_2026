package br.pucminas.cd.aco.round;

import br.pucminas.cd.aco.aco.Tour;

/**
 * Destino do aviso "nó terminou a rodada" (o coordenador).
 *
 * <p>O trabalhador do líder usa a implementação local; os demais chegam via gRPC ({@code RoundDone}).
 */
@FunctionalInterface
public interface RoundSink {
    void onRoundDone(int round, int from, Tour tour);
}
