package com.kwang.study.mathvision.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Component
public class MathVisionArtifactReceipt {
    private final MathVisionTransferSettings settings;
    private final ObjectMapper json;
    private final MathVisionVersionMapper versions;
    private final MathVisionArtifactMapper artifacts;
    public MathVisionArtifactReceipt(MathVisionTransferSettings settings, ObjectMapper json,
            MathVisionVersionMapper versions, MathVisionArtifactMapper artifacts) {
        this.settings = settings; this.json = json; this.versions = versions; this.artifacts = artifacts;
    }
    public String sign(MathVisionTask task, String path, long size, String sha256) {
        ObjectNode body = json.createObjectNode();
        body.put("taskId", task.getId()); body.put("version", task.getCurrentVersion());
        body.put("token", task.getLockToken()); body.put("path", path);
        body.put("size", size); body.put("sha256", sha256);
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().getBytes(StandardCharsets.UTF_8));
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
    }
    public void verify(MathVisionTask task, String receipt, String path) {
        try {
            // Optional scene review can reuse the previously committed artifact, including legacy files.
            var version = versions.findByTaskVersion(task.getId(), task.getCurrentVersion());
            if (version != null && version.getRrVersion() != null) {
                var artifact = artifacts.findByTaskStageVersion(task.getId(), "render_result", version.getRrVersion());
                if (artifact != null && path != null && path.equals(json.readTree(artifact.getArtifactJson()).path("artifactPath").asText())) return;
            }
            if (receipt == null) throw new IllegalArgumentException("Missing artifact receipt");
            String[] parts = receipt.split("\\.");
            if (parts.length != 2 || !MessageDigest.isEqual(mac(parts[0]), Base64.getUrlDecoder().decode(parts[1])))
                throw new IllegalArgumentException("Invalid artifact receipt");
            JsonNode body = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (body.path("taskId").asLong() != task.getId() || body.path("version").asInt() != task.getCurrentVersion()
                    || !body.path("token").asText().equals(task.getLockToken()) || !body.path("path").asText().equals(path))
                throw new IllegalArgumentException("Artifact receipt belongs to another execution");
        } catch (Exception e) { throw new IllegalStateException("Artifact receipt rejected", e); }
    }
    private byte[] mac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException("Cannot sign artifact receipt", e); }
    }
}
