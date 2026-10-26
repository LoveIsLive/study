package com.kwang.study.mathvision.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;

/** Every worker, including the master's local worker, uses this HTTP transport. */
@Component
public class MathVisionTransferClient {
    private final MathVisionTransferSettings settings;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public MathVisionTransferClient(MathVisionTransferSettings settings, ObjectMapper json) { this.settings = settings; this.json = json; }
    public JsonNode upload(MathVisionTask task, Path file, String type) throws IOException {
        ObjectNode init = json.createObjectNode();
        init.put("taskId", task.getId()); init.put("version", task.getCurrentVersion());
        init.put("token", task.getLockToken()); init.put("size", Files.size(file));
        init.put("sha256", sha256(file)); init.put("type", type);
        JsonNode upload = request("POST", "/artifacts/init", json.writeValueAsBytes(init));
        String id = upload.path("uploadId").asText();
        int chunkBytes = upload.path("chunkBytes").asInt();
        if (!id.matches("[a-f0-9]{64}") || chunkBytes < 1024 || chunkBytes > 16 * 1024 * 1024) throw new IOException("Invalid upload session");
        JsonNode status = request("GET", "/artifacts/" + id, null);
        if (!status.path("complete").asBoolean()) {
            try (InputStream source = Files.newInputStream(file)) {
                byte[] buffer = new byte[chunkBytes]; int index = 0;
                for (int count; (count = source.readNBytes(buffer, 0, buffer.length)) > 0; index++) {
                    if (!status.path("chunks").has(Integer.toString(index)))
                        request("PUT", "/artifacts/" + id + "/chunks/" + index, Arrays.copyOf(buffer, count));
                }
            }
        }
        return request("POST", "/artifacts/" + id + "/complete", new byte[0]);
    }
    public byte[] asset(MathVisionTask task, int index) throws IOException {
        String path = "/tasks/" + task.getId() + "/assets/" + index + "?token=" + task.getLockToken();
        return send("GET", path, null);
    }
    private JsonNode request(String method, String path, byte[] bytes) throws IOException {
        return json.readTree(send(method, path, bytes));
    }
    private byte[] send(String method, String path, byte[] bytes) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < Math.max(1, settings.config().getUploadAttempts()); attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Transfer canceled");
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(settings.masterUrl() + "/internal/mathvision" + path))
                        .header("X-MathVision-Key", settings.secret()).header("Content-Type", method.equals("PUT") ? "application/octet-stream" : "application/json")
                        .timeout(Duration.ofMinutes(10)).method(method, bytes == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 200 && response.statusCode() < 300) return response.body();
                if (response.statusCode() < 500 && response.statusCode() != 429) throw new PermanentTransferException("Master rejected transfer: HTTP " + response.statusCode());
                last = new IOException("Master transfer unavailable: HTTP " + response.statusCode());
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("Transfer interrupted"); }
            catch (PermanentTransferException e) { throw e; }
            catch (IOException e) { last = e; }
            try { Thread.sleep(Math.min(4000, 500L << Math.min(attempt, 3))); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("Transfer interrupted"); }
        }
        throw last == null ? new IOException("Transfer failed") : last;
    }
    public static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(); for (byte b : value) out.append(String.format("%02x", b & 255)); return out.toString();
    }
    private static class PermanentTransferException extends IOException { PermanentTransferException(String message) { super(message); } }
}
