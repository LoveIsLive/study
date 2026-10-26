package com.kwang.study.mathvision.service;

import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.model.RenderResult;
import com.kwang.study.mathvision.transfer.MathVisionTransferClient;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;

/** Local artifacts survive upload; only a successful fenced stage commit permits their deletion. */
@Service
public class MathVisionFinalArtifactStorageService {
    private final MathVisionTransferClient client;
    public MathVisionFinalArtifactStorageService(MathVisionTransferClient client) { this.client = client; }
    public StoredArtifact store(MathVisionTask task, RenderResult result) throws IOException {
        if (task == null || task.getId() == null || result == null || result.getArtifactPath() == null)
            throw new IllegalArgumentException("Task and local render artifact are required");
        Path source = Path.of(result.getArtifactPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) throw new IllegalArgumentException("Render artifact file does not exist");
        String type = "geogebra".equalsIgnoreCase(result.getOutputTarget()) || source.toString().endsWith(".html") ? "html" : "mp4";
        var uploaded = client.upload(task, source, type);
        result.setArtifactReceipt(uploaded.path("receipt").asText());
        return new StoredArtifact(uploaded.path("path").asText(), "final." + type, type,
                type.equals("html") ? "text/html" : "video/mp4", Files.size(source));
    }
    public static final class StoredArtifact {
        private final String path, fileName, artifactType, mimeType;
        private final long size;
        public StoredArtifact(String path, String fileName, String artifactType, String mimeType, long size) {
            this.path = path; this.fileName = fileName; this.artifactType = artifactType; this.mimeType = mimeType; this.size = size;
        }
        public String getPath() { return path; }
        public String getFileName() { return fileName; }
        public String getArtifactType() { return artifactType; }
        public String getMimeType() { return mimeType; }
        public long getSize() { return size; }
    }
}
