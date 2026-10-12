package br.pucminas.cd.aco.election;

import br.pucminas.cd.aco.util.Log;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Detector de falha simples: heartbeat no líder a cada 1 s; 3 falhas seguidas disparam eleição. */
public final class LeaderMonitor {
    private static final int MAX_MISSES = 3;

    private final int selfId;
    private final BullyElection election;
    private final ElectionTransport transport;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private int misses;

    public LeaderMonitor(int selfId, BullyElection election, ElectionTransport transport) {
        this.selfId = selfId;
        this.election = election;
        this.transport = transport;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::check, 1, 1, TimeUnit.SECONDS);
    }

    private void check() {
        try {
            int leader = election.leaderId();
            if (leader <= 0 || leader == selfId || transport.heartbeat(leader)) {
                misses = 0;
                return;
            }
            if (++misses >= MAX_MISSES) {
                Log.info("Líder N%d não responde há %d heartbeats; iniciando eleição", leader, misses);
                misses = 0;
                election.startElection();
            }
        } catch (RuntimeException e) {
            // scheduleAtFixedRate cancela a tarefa se uma exceção escapar
            Log.info("Erro no monitor de líder: %s", e);
        }
    }
}
