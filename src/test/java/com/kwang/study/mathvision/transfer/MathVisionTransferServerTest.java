package com.kwang.study.mathvision.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.engine.MathVisionLeaseLostException;
import com.kwang.study.mathvision.mapper.*;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MathVisionTransferServerTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    final MathVisionTaskMapper tasks = mock(MathVisionTaskMapper.class);
    final MathVisionArchiveAdapter archive = mock(MathVisionArchiveAdapter.class);
    MathVisionTransferSettings settings;
    MathVisionArtifactReceipt receipts;
    MathVisionTask task;
    @BeforeEach void setup() {
        var catalog = new MathVisionModelCatalog(); catalog.getDistributed().setTransferRoot(root.toString());
        catalog.getDistributed().setServiceSecret("test-service-secret-with-at-least-32-characters");
        catalog.getDistributed().setUploadChunkBytes(1024);
        settings = new MathVisionTransferSettings(catalog, new MockEnvironment());
        receipts = new MathVisionArtifactReceipt(settings, json, mock(MathVisionVersionMapper.class), mock(MathVisionArtifactMapper.class));
        task = MathVisionTask.builder().id(7L).currentVersion(2).status("running").lockToken(UUID.randomUUID().toString()).build();
        when(tasks.findById(7L)).thenReturn(task); when(tasks.hasLease(7L, task.getLockToken())).thenReturn(1);
    }
    private MathVisionTransferServer server() { return new MathVisionTransferServer(settings, json, tasks, archive, receipts); }
    private ObjectNode metadata(Path input) throws IOException {
        return json.createObjectNode().put("taskId", 7).put("version", 2).put("token", task.getLockToken())
                .put("size", Files.size(input)).put("sha256", MathVisionTransferClient.sha256(input)).put("type", "mp4");
    }
    @Test void resumeSurvivesServerRecreationAndCompleteIsIdempotent() throws Exception {
        Path input = root.resolve("source.mp4"); Files.writeString(input, "x".repeat(1400));
        var first = server(); String id = first.init(metadata(input)).path("uploadId").asText();
        first.chunk(id, 0, new ByteArrayInputStream("x".repeat(1024).getBytes()));
        var restarted = server(); assertTrue(restarted.status(id).path("chunks").has("0"));
        restarted.chunk(id, 1, new ByteArrayInputStream("x".repeat(376).getBytes()));
        ObjectNode completed = restarted.complete(id);
        assertEquals(completed, restarted.complete(id));
        assertEquals(completed, server().complete(id));
        receipts.verify(task, completed.path("receipt").asText(), completed.path("path").asText());
        verify(archive, times(1)).archive(any(), anyString(), eq("video/mp4"), anyString());
    }
    @Test void conflictingDuplicateChunkIsRejected() throws Exception {
        Path input = root.resolve("source.mp4"); Files.writeString(input, "abc");
        var server = server(); String id = server.init(metadata(input)).path("uploadId").asText();
        server.chunk(id, 0, new ByteArrayInputStream("abc".getBytes()));
        server.chunk(id, 0, new ByteArrayInputStream("abc".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> server.chunk(id, 0, new ByteArrayInputStream("def".getBytes())));
    }
    @Test void expiredLeaseCannotUploadOrComplete() throws Exception {
        Path input = root.resolve("source.mp4"); Files.writeString(input, "abc");
        var server = server(); String id = server.init(metadata(input)).path("uploadId").asText();
        when(tasks.hasLease(7L, task.getLockToken())).thenReturn(0);
        assertThrows(MathVisionLeaseLostException.class, () -> server.chunk(id, 0, new ByteArrayInputStream("abc".getBytes())));
        assertThrows(MathVisionLeaseLostException.class, () -> server.complete(id)); verifyNoInteractions(archive);
    }
    @Test void concurrentCompleteOnlyArchivesOnce() throws Exception {
        Path input = root.resolve("source.mp4"); Files.writeString(input, "abc");
        var server = server(); String id = server.init(metadata(input)).path("uploadId").asText();
        server.chunk(id, 0, new ByteArrayInputStream("abc".getBytes()));
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<ObjectNode>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) results.add(pool.submit(() -> server.complete(id)));
            for (var result : results) assertTrue(result.get(10, TimeUnit.SECONDS).path("complete").asBoolean());
            verify(archive, times(1)).archive(any(), anyString(), anyString(), anyString());
        } finally { pool.shutdownNow(); }
    }
    @Test void receiptCannotBeReusedForAnotherPathOrExecution() throws Exception {
        String receipt = receipts.sign(task, "/mathvision/a.mp4", 3, "abc");
        assertThrows(IllegalStateException.class, () -> receipts.verify(task, receipt, "/mathvision/b.mp4"));
        task.setLockToken(UUID.randomUUID().toString());
        assertThrows(IllegalStateException.class, () -> receipts.verify(task, receipt, "/mathvision/a.mp4"));
        assertThrows(IllegalArgumentException.class, () -> server().status("../../file"));
    }

    @Test void realHttpClientRetriesLostCompletionResponseWithoutDuplicateArchive() throws Exception {
        var server = server();
        var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var lostResponse = new java.util.concurrent.atomic.AtomicBoolean(true);
        http.createContext("/internal/mathvision/artifacts", exchange -> {
            try {
                if (!settings.secret().equals(exchange.getRequestHeaders().getFirst("X-MathVision-Key"))) {
                    exchange.sendResponseHeaders(401, -1); return;
                }
                String path = exchange.getRequestURI().getPath().substring("/internal/mathvision/artifacts/".length());
                String[] parts = path.split("/"); ObjectNode result;
                if (path.equals("init")) result = server.init((ObjectNode) json.readTree(exchange.getRequestBody()));
                else if (parts.length == 3) { server.chunk(parts[0], Integer.parseInt(parts[2]), exchange.getRequestBody()); result = json.createObjectNode(); }
                else if (parts.length == 2) {
                    result = server.complete(parts[0]);
                    if (lostResponse.getAndSet(false)) { exchange.sendResponseHeaders(503, -1); return; }
                } else result = server.status(parts[0]);
                byte[] response = json.writeValueAsBytes(result); exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Exception error) { exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        http.start();
        try {
            var clientSettings = new MathVisionTransferSettings(new MathVisionModelCatalog(), new MockEnvironment()
                    .withProperty("mathvision.master-url", "http://127.0.0.1:" + http.getAddress().getPort())
                    .withProperty("mathvision.service-secret", settings.secret()));
            Path video = root.resolve("http-video.mp4"); Files.writeString(video, "frame".repeat(500));
            ObjectNode uploaded = (ObjectNode) new MathVisionTransferClient(clientSettings, json).upload(task, video, "mp4");
            assertTrue(uploaded.path("complete").asBoolean()); assertFalse(lostResponse.get()); assertTrue(Files.exists(video));
            verify(archive, times(1)).archive(any(), anyString(), anyString(), anyString());
        } finally { http.stop(0); }
    }
}
