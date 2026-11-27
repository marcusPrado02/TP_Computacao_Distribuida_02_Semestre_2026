package br.pucminas.cd.aco.replica;

import br.pucminas.cd.aco.aco.Tour;

/**
 * Estado completo de uma réplica, transferido por {@code FetchState} e gravado no checkpoint.
 *
 * @param version   carimbo da matriz
 * @param tau       matriz n×n em row-major (cópia; ninguém mais a altera)
 * @param best      melhor rota conhecida, ou null
 * @param lastRound última rodada vista por este nó (o novo líder continua da maior)
 */
public record ReplicaSnapshot(Version version, double[] tau, Tour best, int lastRound) {
    public ReplicaSnapshot {
        if (version == null) throw new IllegalArgumentException("versão obrigatória");
        if (tau == null) throw new IllegalArgumentException("matriz obrigatória");
        if (lastRound < 0) throw new IllegalArgumentException("rodada negativa: " + lastRound);
    }
}
