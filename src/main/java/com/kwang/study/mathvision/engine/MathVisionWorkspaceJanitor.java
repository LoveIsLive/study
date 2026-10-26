package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@ConditionalOnProperty(name = "mathvision.worker.enabled", havingValue = "true")
public class MathVisionWorkspaceJanitor {
    private final Path root;
    private final MathVisionTaskMapper tasks;
    private final MathVisionModelCatalog catalog;
    public MathVisionWorkspaceJanitor(@Value("${mathvision.render.output-root:mathvision-runs}") String root,
            MathVisionTaskMapper tasks, MathVisionModelCatalog catalog) {
        this.root = Path.of(root).toAbsolutePath().normalize(); this.tasks = tasks; this.catalog = catalog;
    }
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void cleanup() {
        if (!Files.isDirectory(root)) return;
        Instant cutoff = Instant.now().minus(Math.max(1, catalog.getDistributed().getRetentionHours()), ChronoUnit.HOURS);
        try (var paths = Files.find(root, 3, (p, a) -> a.isDirectory() && p.getFileName().toString().matches("run-[a-f0-9-]{36}"))) {
            for (Path run : paths.collect(java.util.stream.Collectors.toList())) {
                try {
                    if (!Files.getLastModifiedTime(run).toInstant().isBefore(cutoff)) continue;
                    long id = Long.parseLong(run.getParent().getParent().getFileName().toString().substring(5));
                    var task = tasks.findById(id);
                    String token = run.getFileName().toString().substring(4);
                    if (task != null && token.equals(task.getLockToken()) && tasks.hasLease(id, token) == 1) continue;
                    deleteRun(root, run);
                } catch (Exception ignored) { /* Database unavailability must never authorize deletion. */ }
            }
        } catch (IOException ignored) { }
    }
    public static void deleteRun(Path root, Path run) throws IOException {
        Path resolved = run.toAbsolutePath().normalize();
        if (resolved.equals(root) || !resolved.startsWith(root)) throw new IOException("Unsafe workspace cleanup");
        Files.walkFileTree(resolved, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.deleteIfExists(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error; Files.deleteIfExists(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
