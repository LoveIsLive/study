package com.kwang.study.mathvision.workflow.ai;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import static org.junit.jupiter.api.Assertions.*;

class MathVisionModelReachabilityTest {
    private static final URI ENDPOINT = URI.create("https://gateway.invalid/v1/chat/completions");

    @Test void probeBeanIsOnlyCreatedForWorkers() {
        var context = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(MathVisionModelCatalog.class, MathVisionModelCatalog::new)
                .withUserConfiguration(MathVisionModelReachability.class);
        context.withPropertyValues("mathvision.worker.enabled=false").run(application ->
                assertEquals(0, application.getBeansOfType(MathVisionModelReachability.class).size()));
        context.withPropertyValues("mathvision.worker.enabled=true").run(application ->
                assertEquals(1, application.getBeansOfType(MathVisionModelReachability.class).size()));
    }

    @Test void coldChecksAreAsynchronousAndCoalescedUntilTtlExpires() {
        var jobs = new ArrayList<Runnable>();
        var time = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
        AtomicInteger calls = new AtomicInteger();
        var catalog = new MathVisionModelCatalog();
        try (var health = service(catalog, jobs, time, (uri, connect, request) -> {
            calls.incrementAndGet(); assertEquals(2000, connect); assertEquals(5000, request);
            return MathVisionModelReachability.classify(405, "method not allowed");
        })) {
            assertFalse(health.canAccess(ENDPOINT, 1L));
            assertFalse(health.canAccess(ENDPOINT, 2L));
            assertEquals(1, jobs.size()); assertEquals(0, calls.get());
            jobs.remove(0).run();
            assertTrue(health.canAccess(ENDPOINT, 1L));
            assertTrue(health.canAccess(ENDPOINT, 2L)); assertEquals(1, calls.get());
            time.addAndGet(TimeUnit.SECONDS.toNanos(80));
            assertFalse(health.canAccess(ENDPOINT, 1L)); assertEquals(1, jobs.size());
        }
    }

    @Test void failedNetworkIsCooledDownThenRetriedAndCanRecover() {
        var jobs = new ArrayList<Runnable>(); var time = new AtomicLong(100);
        AtomicInteger attempts = new AtomicInteger();
        try (var health = service(new MathVisionModelCatalog(), jobs, time, (uri, connect, request) -> {
            if (attempts.incrementAndGet() == 1) throw new ConnectException("unreachable");
            return MathVisionModelReachability.classify(401, "authentication required");
        })) {
            assertFalse(health.canAccess(ENDPOINT, 1L)); jobs.remove(0).run();
            assertFalse(health.canAccess(ENDPOINT, 1L)); assertEquals(0, jobs.size());
            time.addAndGet(TimeUnit.SECONDS.toNanos(40));
            assertFalse(health.canAccess(ENDPOINT, 1L)); jobs.remove(0).run();
            assertTrue(health.canAccess(ENDPOINT, 1L)); assertEquals(2, attempts.get());
        }
    }

    @Test void sameProtocolOnDifferentAddressesAndDifferentWorkersAreIndependent() {
        var jobs = new ArrayList<Runnable>(); var otherJobs = new ArrayList<Runnable>(); var time = new AtomicLong(100);
        var probe = (MathVisionModelReachability.Probe) (uri, connect, request) -> MathVisionModelReachability.classify(200, "");
        try (var first = service(new MathVisionModelCatalog(), jobs, time, probe);
             var second = service(new MathVisionModelCatalog(), otherJobs, time, probe)) {
            assertFalse(first.canAccess(ENDPOINT, 1L)); jobs.remove(0).run();
            assertTrue(first.canAccess(ENDPOINT, 1L));
            assertFalse(second.canAccess(ENDPOINT, 1L));
            assertFalse(first.canAccess(URI.create("https://other.invalid/v1/chat/completions"), 2L));
            assertEquals(1, otherJobs.size()); assertEquals(1, jobs.size());
        }
    }

    @Test void actualNetworkFailureCannotBeOverwrittenByAnOlderProbe() {
        var jobs = new ArrayList<Runnable>(); var time = new AtomicLong(100);
        try (var health = service(new MathVisionModelCatalog(), jobs, time,
                (uri, connect, request) -> MathVisionModelReachability.classify(200, ""))) {
            assertFalse(health.canAccess(ENDPOINT, 1L));
            health.observeNetworkFailure(ENDPOINT);
            jobs.remove(0).run();
            assertFalse(health.canAccess(ENDPOINT, 1L)); assertEquals(0, jobs.size());
            health.observeResponse(ENDPOINT, 200, "ok");
            assertTrue(health.canAccess(ENDPOINT, 1L));
        }
    }

    @Test void differentiatesRegionRestrictionsTemporaryFailuresAndAuthentication() {
        assertFalse(MathVisionModelReachability.classify(403, "unsupported_country_region_territory").available);
        assertFalse(MathVisionModelReachability.classify(403, "Your country is not supported").available);
        assertFalse(MathVisionModelReachability.classify(429, "").available);
        assertFalse(MathVisionModelReachability.classify(503, "").available);
        assertFalse(MathVisionModelReachability.classify(302, "redirect").available);
        for (int status : List.of(200, 400, 401, 403, 404, 405))
            assertTrue(MathVisionModelReachability.classify(status, "API key required").available);
    }

    @Test void saturatedProbeQueueFailsClosedAndCanBeRetried() {
        var attempts = new AtomicInteger();
        var health = new MathVisionModelReachability(new MathVisionModelCatalog(), runnable -> {
            attempts.incrementAndGet(); throw new java.util.concurrent.RejectedExecutionException();
        }, () -> 100L, (uri, connect, request) -> { fail("probe ran inline"); return null; });
        assertFalse(health.canAccess(ENDPOINT, 1L));
        assertFalse(health.canAccess(ENDPOINT, 1L)); assertEquals(2, attempts.get());
        health.close();
    }

    @Test void probeUsesActualEndpointWithoutKeyPromptOrModelInference() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var seen = new java.util.concurrent.CompletableFuture<String>();
        server.createContext("/v1/chat/completions", exchange -> {
            seen.complete(exchange.getRequestMethod() + "|" + exchange.getRequestURI() + "|"
                    + exchange.getRequestHeaders().getFirst("Authorization") + "|"
                    + exchange.getRequestBody().readAllBytes().length);
            byte[] body = "method not allowed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(405, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            var result = new MathVisionModelReachability.HttpProbe().check(endpoint, 1000, 2000);
            assertTrue(result.available);
            assertEquals("GET|/v1/chat/completions|null|0", seen.get(2, TimeUnit.SECONDS));
        } finally { server.stop(0); }
    }

    @Test void probeHasTotalTimeoutEvenWhenServerStallsAfterSendingHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var finish = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('a'); exchange.getResponseBody().flush();
            try { finish.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stall");
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> assertThrows(Exception.class,
                    () -> new MathVisionModelReachability.HttpProbe().check(endpoint, 500, 150)));
        } finally { finish.countDown(); server.stop(0); }
    }

    @Test void probeLimitsLargeGatewayBodies() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var finish = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/large", exchange -> {
            byte[] body = new byte[16384]; java.util.Arrays.fill(body, (byte) 'a');
            exchange.sendResponseHeaders(200, body.length * 2);
            try {
                exchange.getResponseBody().write(body); exchange.getResponseBody().flush();
                try { finish.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            } finally { exchange.close(); }
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/large");
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
                    assertTrue(new MathVisionModelReachability.HttpProbe().check(endpoint, 500, 1500).available));
        } finally { finish.countDown(); server.stop(0); }
    }

    private MathVisionModelReachability service(MathVisionModelCatalog catalog, List<Runnable> jobs,
                                                AtomicLong time, MathVisionModelReachability.Probe probe) {
        return new MathVisionModelReachability(catalog, jobs::add, time::get, probe);
    }
}
