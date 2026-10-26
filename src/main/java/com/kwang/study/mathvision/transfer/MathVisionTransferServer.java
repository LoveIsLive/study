package com.kwang.study.mathvision.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.engine.MathVisionLeaseLostException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Durable transfer state belongs to the single master, not to an in-memory upload map. */
@Service
@ConditionalOnProperty(name = "mathvision.worker.enabled", havingValue = "false", matchIfMissing = true)
public class MathVisionTransferServer {
    private final Object[] locks = new Object[64];
    private final java.util.concurrent.ScheduledExecutorService cleaner = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mathvision-transfer-cleanup"); thread.setDaemon(true); return thread;
    });
    private final MathVisionTransferSettings settings;
    private final ObjectMapper json;
    private final MathVisionTaskMapper tasks;
    private final MathVisionArchiveAdapter archive;
    private final MathVisionArtifactReceipt receipts;
    @org.springframework.beans.factory.annotation.Autowired
    private com.kwang.study.mathvision.mapper.MathVisionArtifactMapper artifacts;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;
    public MathVisionTransferServer(MathVisionTransferSettings settings, ObjectMapper json,
            MathVisionTaskMapper tasks, MathVisionArchiveAdapter archive, MathVisionArtifactReceipt receipts) {
        this.settings = settings; this.json = json; this.tasks = tasks; this.archive = archive; this.receipts = receipts;
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }
    private Object mutex(String id) { return locks[(id.hashCode() & Integer.MAX_VALUE) % locks.length]; }
    public ObjectNode init(ObjectNode request) throws Exception {
        long size = request.path("size").asLong();
        String hash = request.path("sha256").asText(); String type = request.path("type").asText();
        if (size <= 0 || size > Math.min(settings.config().getMaxArtifactBytes(), 1024L * 1024 * 1024)
                || !hash.matches("[a-f0-9]{64}") || !(type.equals("mp4") || type.equals("html")))
            throw new IllegalArgumentException("Unsupported artifact metadata");
        validate(request);
        String key = request.path("taskId").asLong() + ":" + request.path("version").asInt() + ":" + request.path("token").asText() + ":" + hash + ":" + type;
        String id = MathVisionTransferClient.hex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        synchronized (mutex(id)) {
            Path dir = directory(id); Files.createDirectories(dir);
            Path manifest = dir.resolve("manifest.json");
            if (Files.exists(manifest)) {
                ObjectNode old = read(id);
                if (old.path("size").asLong() != size) throw new IllegalArgumentException("Upload size changed");
                return status(id);
            }
            ObjectNode state = request.deepCopy(); state.put("uploadId", id);
            state.put("chunkBytes", Math.max(1024, Math.min(settings.config().getUploadChunkBytes(), 16 * 1024 * 1024)));
            state.put("complete", false); state.putObject("chunks");
            state.put("archivePath", "/mathvision/task-" + state.path("taskId").asLong() + "/v" + state.path("version").asInt()
                    + "/runs/" + state.path("token").asText() + "/" + hash + "." + type);
            save(id, state); return state;
        }
    }
    public ObjectNode status(String id) throws Exception {
        synchronized (mutex(id)) { ObjectNode state = read(id); validate(state); return state; }
    }
    public void chunk(String id, int index, InputStream source) throws Exception {
        synchronized (mutex(id)) {
            ObjectNode state = read(id); validate(state);
            long size = state.path("size").asLong(); int bytes = state.path("chunkBytes").asInt();
            long offset = (long) index * bytes;
            if (index < 0 || offset >= size || state.path("complete").asBoolean()) throw new IllegalArgumentException("Invalid chunk");
            int expected = (int) Math.min(bytes, size - offset);
            Path part = directory(id).resolve(index + ".part"); Path temp = directory(id).resolve(UUID.randomUUID() + ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(temp)) {
                    byte[] buffer = new byte[65536]; int remaining = expected;
                    while (remaining > 0) {
                        int count = source.read(buffer, 0, Math.min(buffer.length, remaining));
                        if (count < 0) throw new IllegalArgumentException("Incomplete chunk");
                        out.write(buffer, 0, count); remaining -= count;
                    }
                    if (source.read() != -1) throw new IllegalArgumentException("Oversized chunk");
                }
                String hash = MathVisionTransferClient.sha256(temp);
                if (Files.exists(part) && !hash.equals(MathVisionTransferClient.sha256(part))) throw new IllegalArgumentException("Chunk content changed");
                atomicMove(temp, part);
                ((ObjectNode) state.path("chunks")).put(Integer.toString(index), hash); save(id, state);
            } finally { Files.deleteIfExists(temp); }
        }
    }
    public ObjectNode complete(String id) throws Exception {
        synchronized (mutex(id)) {
            ObjectNode state = read(id); MathVisionTask task = validate(state);
            if (state.path("complete").asBoolean()) return state;
            Path merged = directory(id).resolve("artifact.ready");
            int total = (int) ((state.path("size").asLong() + state.path("chunkBytes").asInt() - 1) / state.path("chunkBytes").asInt());
            try (OutputStream out = Files.newOutputStream(merged)) {
                for (int index = 0; index < total; index++) {
                    Path part = directory(id).resolve(index + ".part");
                    if (!Files.exists(part) || !MathVisionTransferClient.sha256(part).equals(state.path("chunks").path(Integer.toString(index)).asText()))
                        throw new IllegalArgumentException("Missing or invalid chunk " + index);
                    Files.copy(part, out);
                }
            }
            if (Files.size(merged) != state.path("size").asLong() || !MathVisionTransferClient.sha256(merged).equals(state.path("sha256").asText()))
                throw new IllegalArgumentException("Artifact checksum mismatch");
            validate(state);
            archive.archive(merged, state.path("archivePath").asText(), state.path("type").asText().equals("mp4") ? "video/mp4" : "text/html", state.path("sha256").asText());
            validate(state); // A lease may have expired during a slow disk archive. Never publish stale output.
            state.put("path", state.path("archivePath").asText());
            state.put("receipt", receipts.sign(task, state.path("path").asText(), state.path("size").asLong(), state.path("sha256").asText()));
            state.put("complete", true); save(id, state); return state;
        }
    }
    public MathVisionTask validate(ObjectNode state) {
        long id = state.path("taskId").asLong(); String token = state.path("token").asText();
        if (!token.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid execution token");
        MathVisionTask task = tasks.findById(id);
        if (task == null || task.getCurrentVersion() != state.path("version").asInt()
                || !token.equals(task.getLockToken()) || Boolean.TRUE.equals(task.getCancelRequested()) || tasks.hasLease(id, token) != 1)
            throw new MathVisionLeaseLostException();
        return task;
    }
    private Path directory(String id) {
        if (!id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid upload ID");
        return settings.root().resolve(id);
    }
    private ObjectNode read(String id) throws IOException {
        return (ObjectNode) json.readTree(directory(id).resolve("manifest.json").toFile());
    }
    private void save(String id, ObjectNode state) throws IOException {
        Path target = directory(id).resolve("manifest.json"); Path temp = directory(id).resolve(UUID.randomUUID() + ".tmp");
        try {
            json.writeValue(temp.toFile(), state);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            atomicMove(temp, target);
        } finally { Files.deleteIfExists(temp); }
    }
    private void atomicMove(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startCleanup() {
        cleaner.scheduleWithFixedDelay(() -> {
            try { cleanupExpiredTransfers(); } catch (Exception ignored) { }
        }, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    @javax.annotation.PreDestroy public void stopCleanup() { cleaner.shutdownNow(); }

    public void cleanupExpiredTransfers() throws IOException {
        if (!Files.isDirectory(settings.root())) return;
        long cutoff = System.currentTimeMillis() - java.util.concurrent.TimeUnit.HOURS.toMillis(Math.max(1, settings.config().getRetentionHours()));
        try (var directories = Files.list(settings.root())) {
            for (Path dir : directories.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).collect(java.util.stream.Collectors.toList())) {
                String id = dir.getFileName().toString();
                if (!id.matches("[a-f0-9]{64}")) continue;
                synchronized (mutex(id)) {
                    try {
                        Path manifest = dir.resolve("manifest.json");
                        if (!Files.exists(manifest) || Files.getLastModifiedTime(manifest).toMillis() > cutoff) continue;
                        ObjectNode state = read(id);
                        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
                            var task = tasks.lockById(state.path("taskId").asLong());
                            if (task != null && state.path("token").asText().equals(task.getLockToken())
                                    && tasks.hasLease(task.getId(), task.getLockToken()) == 1) return;
                            String path = state.path("archivePath").asText();
                            if (artifacts.countPathReferences(path) == 0) {
                                try { archive.deleteUnreferenced(path); }
                                catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                            }
                            try { com.kwang.study.mathvision.engine.MathVisionWorkspaceJanitor.deleteRun(settings.root(), dir); }
                            catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                        });
                    } catch (Exception ignored) { /* Keep data if ownership or references cannot be checked. */ }
                }
            }
        }
    }
}
