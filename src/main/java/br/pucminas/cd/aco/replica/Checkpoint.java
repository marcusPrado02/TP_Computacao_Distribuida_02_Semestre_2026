package br.pucminas.cd.aco.replica;

import br.pucminas.cd.aco.aco.Tour;
import br.pucminas.cd.aco.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Persistência da réplica em disco ({@code STATE_DIR}): sobrevive a um nó que cai e volta e até
 * ao cluster inteiro reiniciado (sem checkpoint, todos voltariam para a matriz inicial).
 *
 * <p>Gravação <b>atômica</b>: escreve num arquivo temporário e troca com {@code rename}. Um crash
 * no meio da escrita deixa o checkpoint anterior intacto, nunca um arquivo pela metade.
 *
 * <p>Gravação <b>coalescida</b>: {@link #markDirty} só agenda; se várias escritas chegam enquanto
 * uma gravação está na fila, grava-se uma vez só o estado mais recente. A thread do trabalhador
 * (dentro da seção crítica) nunca espera o disco.
 *
 * <p>O arquivo leva uma "impressão digital" da instância (número de cidades e comprimento do vizinho
 * mais próximo). Checkpoint de outra instância TSP é ignorado em vez de corromper a execução.
 */
public final class Checkpoint implements AutoCloseable {
    private static final int MAGIC = 0x41434F32; // "ACO2"

    private final Path file;
    private final int n;
    private final double fingerprint;
    private final Supplier<ReplicaSnapshot> state;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "checkpoint");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    /**
     * @param dir         diretório (criado se não existir)
     * @param nodeId      um arquivo por nó: {@code node-<id>.ckpt}
     * @param n           cidades da instância
     * @param fingerprint comprimento do vizinho mais próximo da instância
     * @param state       de onde ler o estado na hora de gravar
     */
    public Checkpoint(Path dir, int nodeId, int n, double fingerprint, Supplier<ReplicaSnapshot> state)
            throws IOException {
        Files.createDirectories(dir);
        this.file = dir.resolve("node-" + nodeId + ".ckpt");
        this.n = n;
        this.fingerprint = fingerprint;
        this.state = state;
    }

    public Path file() {
        return file;
    }

    /** Agenda uma gravação do estado atual (não bloqueia). */
    public void markDirty() {
        if (!dirty.compareAndSet(false, true)) return; // já há gravação pendente; ela pega o mais novo
        try {
            writer.submit(() -> {
                dirty.set(false);
                try {
                    save(state.get());
                } catch (IOException | RuntimeException e) {
                    Log.info("Falha ao gravar checkpoint %s: %s", file, e);
                }
            });
        } catch (RejectedExecutionException e) {
            dirty.set(false); // nó sendo desligado
        }
    }

    /** Grava agora, de forma atômica. */
    public void save(ReplicaSnapshot s) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(n);
            out.writeDouble(fingerprint);
            out.writeLong(s.version().number());
            out.writeInt(s.version().writer());
            out.writeInt(s.lastRound());
            int[] cities = s.best() == null ? new int[0] : s.best().cities();
            out.writeInt(cities.length);
            for (int c : cities) out.writeInt(c);
            out.writeDouble(s.best() == null ? 0 : s.best().length());
            out.writeInt(s.tau().length);
            for (double v : s.tau()) out.writeDouble(v);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Lê o checkpoint, se existir e for desta instância. Arquivo corrompido, truncado ou de outra
     * instância é ignorado (com log): o nó segue e recupera o estado dos peers.
     */
    public Optional<ReplicaSnapshot> load() {
        if (!Files.exists(file)) return Optional.empty();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) return reject("formato desconhecido");
            int fileN = in.readInt();
            double fileFingerprint = in.readDouble();
            if (fileN != n || Math.abs(fileFingerprint - fingerprint) > 1e-6) {
                return reject("é de outra instância TSP (" + fileN + " cidades)");
            }
            Version version = new Version(in.readLong(), in.readInt());
            int lastRound = in.readInt();
            int len = in.readInt();
            if (len < 0 || len > n) return reject("rota com tamanho inválido");
            int[] cities = new int[len];
            for (int i = 0; i < len; i++) cities[i] = in.readInt();
            double bestLength = in.readDouble();
            int tauLen = in.readInt();
            if (tauLen != n * n) return reject("matriz com tamanho inválido");
            double[] tau = new double[tauLen];
            for (int i = 0; i < tauLen; i++) tau[i] = in.readDouble();
            Tour best = len == 0 ? null : new Tour(cities, bestLength);
            return Optional.of(new ReplicaSnapshot(version, tau, best, lastRound));
        } catch (IOException | IllegalArgumentException e) {
            return reject("ilegível (" + e + ")");
        }
    }

    private Optional<ReplicaSnapshot> reject(String why) {
        Log.info("Checkpoint %s ignorado: %s", file, why);
        return Optional.empty();
    }

    /** Termina a gravação pendente (se houver) antes de sair. */
    @Override
    public void close() throws InterruptedException {
        writer.shutdown();
        writer.awaitTermination(5, TimeUnit.SECONDS);
    }
}
