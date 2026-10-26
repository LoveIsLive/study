package com.kwang.study.mathvision.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.transfer.MathVisionTransferClient;
import com.kwang.study.mathvision.workflow.model.RenderResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.io.*;
import java.util.Comparator;

/** A render/upload checkpoint is local recovery data, never a user-visible artifact reference. */
@Component
public class MathVisionLocalArtifactRecovery {
    private final ObjectMapper json;
    private final Path root;
    public MathVisionLocalArtifactRecovery(ObjectMapper json, @Value("${mathvision.render.output-root:mathvision-runs}") String root) {
        this.json = json; this.root = Path.of(root).toAbsolutePath().normalize();
    }
    public Path checkpoint(Path output, String fingerprint, RenderResult result, JsonNode geometry) throws IOException {
        Path file = output.getParent().resolve("local-render.json");
        Path artifact = Path.of(result.getArtifactPath()).toAbsolutePath().normalize();
        if (!artifact.startsWith(root) || !Files.isRegularFile(artifact)) throw new IOException("Invalid recovery artifact");
        var state = json.createObjectNode(); state.put("fingerprint", fingerprint);
        state.put("sha256", MathVisionTransferClient.sha256(artifact));
        state.set("render", json.valueToTree(result)); state.set("geometry", geometry);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("local-render.tmp"); json.writeValue(tmp.toFile(), state);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }
    public Recovered restore(MathVisionTask task, Path output, String fingerprint) {
        Path taskRoot = root.resolve("task-" + task.getId());
        if (!Files.isDirectory(taskRoot)) return null;
        try (var paths = Files.find(taskRoot, 4, (p, a) -> a.isRegularFile() && p.getFileName().toString().equals("local-render.json"))) {
            for (Path file : paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) {
                try {
                    JsonNode state = json.readTree(file.toFile());
                    if (!fingerprint.equals(state.path("fingerprint").asText())) continue;
                    RenderResult result = json.treeToValue(state.path("render"), RenderResult.class);
                    Path source = Path.of(result.getArtifactPath()).toAbsolutePath().normalize();
                    if (!source.startsWith(taskRoot) || !Files.isRegularFile(source)
                            || !state.path("sha256").asText().equals(MathVisionTransferClient.sha256(source))) continue;
                    Path target = output.resolve("retained/final." + ("html".equals(result.getArtifactType()) ? "html" : "mp4"));
                    Files.createDirectories(target.getParent());
                    if (!source.equals(target)) Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    result.setArtifactPath(target.toString()); result.setStorageArtifactPath(null); result.setArtifactReceipt(null);
                    if ("mp4".equals(result.getArtifactType())) result.setVideoPath(target.toString());
                    JsonNode geometry = state.get("geometry");
                    if (geometry != null && !geometry.isNull()) {
                        Path report = output.resolve("geometry.json"); json.writeValue(report.toFile(), geometry); result.setGeometryPath(report.toString());
                    }
                    return new Recovered(result, geometry);
                } catch (Exception ignored) { }
            }
        } catch (IOException ignored) { }
        return null;
    }
    public static final class Recovered {
        public final RenderResult render;
        public final JsonNode geometry;
        Recovered(RenderResult render, JsonNode geometry) { this.render = render; this.geometry = geometry; }
    }
}
