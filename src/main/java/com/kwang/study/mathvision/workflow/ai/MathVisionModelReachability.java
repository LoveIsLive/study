package com.kwang.study.mathvision.workflow.ai;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Worker-local, bounded, asynchronous network checks. Never changes shared credential state. */
@Component
@ConditionalOnProperty(name = "mathvision.worker.enabled", havingValue = "true")
public class MathVisionModelReachability implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MathVisionModelReachability.class);
    private static final int MAX_ENDPOINTS = 1024;
    private static final Pattern REGION_DENIED = Pattern.compile(
            "(?is)(unsupported_country_region_territory|(?:country|region|territory|location).{0,100}(?:not supported|unsupported|blocked|restricted|not allowed)|(?:not supported|unsupported|blocked|restricted).{0,100}(?:country|region|territory))");
    private final MathVisionModelCatalog catalog;
    private final Executor executor;
    private final LongSupplier clock;
    private final Probe probe;
    private final Map<URI, Entry> entries = new LinkedHashMap<>(32, 0.75f, true);

    @org.springframework.beans.factory.annotation.Autowired
    public MathVisionModelReachability(MathVisionModelCatalog catalog) {
        this.catalog = catalog;
        this.clock = System::nanoTime;
        this.executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), runnable -> {
            Thread thread = new Thread(runnable, "mathvision-model-probe"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.probe = new HttpProbe();
    }

    MathVisionModelReachability(MathVisionModelCatalog catalog, Executor executor, LongSupplier clock, Probe probe) {
        this.catalog = catalog; this.executor = executor; this.clock = clock; this.probe = probe;
    }

    public boolean canAccess(URI endpoint, Long taskId) {
        Entry entry;
        long generation;
        synchronized (entries) {
            entry = entry(endpoint);
            long now = clock.getAsLong();
            if (entry.result != null && now - entry.expires < 0) return entry.result.available;
            if (entry.checking) return false;
            entry.checking = true;
            generation = entry.generation;
        }
        final Entry checking = entry;
        try {
            executor.execute(() -> check(endpoint, checking, generation, taskId));
        } catch (RejectedExecutionException full) {
            synchronized (entries) { checking.checking = false; }
        }
        return false; // An unknown or expired endpoint cannot acquire a task lease.
    }

    /** Actual requests are stronger evidence than a preceding unauthenticated probe. */
    public void observeResponse(URI endpoint, int status, String body) {
        observe(endpoint, classify(status, body));
    }

    public void observeNetworkFailure(URI endpoint) {
        observe(endpoint, Result.unavailable("model request network failure"));
    }

    private void observe(URI endpoint, Result result) {
        synchronized (entries) {
            Entry entry = entry(endpoint);
            entry.generation++;
            update(endpoint, entry, result, null);
        }
    }

    private void check(URI endpoint, Entry entry, long generation, Long taskId) {
        Result result;
        try {
            MathVisionModelCatalog.ModelReachabilityCatalog config = settings();
            result = probe.check(endpoint, Math.max(1, config.getConnectTimeoutMillis()),
                    Math.max(1, config.getRequestTimeoutMillis()));
        } catch (InterruptedException canceled) {
            Thread.currentThread().interrupt();
            result = Result.unavailable("probe interrupted");
        } catch (Exception failure) {
            // Exception messages can include custom URL credentials. Only log the type.
            Throwable cause = failure;
            while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null)
                cause = cause.getCause();
            result = Result.unavailable(cause.getClass().getSimpleName());
        }
        synchronized (entries) {
            entry.checking = false;
            if (entries.get(endpoint) == entry && generation == entry.generation) update(endpoint, entry, result, taskId);
        }
    }

    private Entry entry(URI endpoint) {
        Entry found = entries.get(endpoint);
        if (found != null) return found;
        if (entries.size() >= MAX_ENDPOINTS) entries.remove(entries.keySet().iterator().next());
        Entry entry = new Entry(); entries.put(endpoint, entry); return entry;
    }

    private void update(URI endpoint, Entry entry, Result result, Long taskId) {
        Result previous = entry.result;
        entry.result = result;
        MathVisionModelCatalog.ModelReachabilityCatalog config = settings();
        int ttl = result.available ? config.getSuccessTtlSeconds() : config.getFailureTtlSeconds();
        // Small independent jitter prevents workers from probing a failed service simultaneously.
        long millis = Math.max(1, ttl) * 1000L;
        millis += ThreadLocalRandom.current().nextLong(Math.max(1, millis / 5));
        entry.expires = clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(millis);
        if (previous == null || previous.available != result.available || !previous.reason.equals(result.reason)) {
            log.info("MathVision 模型服务连通性更新, worker={}, taskId={}, endpoint={}, canClaim={}, reason={}",
                    System.getenv().getOrDefault("MATHVISION_WORKER_ID", System.getenv().getOrDefault("HOSTNAME", "local")),
                    taskId, MathVisionModelEndpoint.logAddress(endpoint), result.available, result.reason);
        }
    }

    private MathVisionModelCatalog.ModelReachabilityCatalog settings() {
        var config = catalog.getDistributed().getModelReachability();
        return config == null ? new MathVisionModelCatalog.ModelReachabilityCatalog() : config;
    }

    static Result classify(int status, String body) {
        if (status == 403 && REGION_DENIED.matcher(body == null ? "" : body).find())
            return Result.unavailable("HTTP 403 region restriction");
        if (status == 429 || status >= 500) return Result.unavailable("HTTP " + status + " temporary cooldown");
        if (status >= 200 && status < 500 && (status < 300 || status >= 400))
            return new Result(true, "HTTP " + status + " network reachable");
        return Result.unavailable("HTTP " + status + " unexpected response");
    }

    @PreDestroy public void close() {
        if (executor instanceof ExecutorService) ((ExecutorService) executor).shutdownNow();
        synchronized (entries) { entries.clear(); }
    }

    @FunctionalInterface interface Probe {
        Result check(URI endpoint, int connectTimeoutMillis, int requestTimeoutMillis) throws Exception;
    }
    static final class Result {
        final boolean available;
        final String reason;
        Result(boolean available, String reason) { this.available = available; this.reason = reason; }
        static Result unavailable(String reason) { return new Result(false, reason); }
    }
    private static final class Entry {
        Result result;
        long expires, generation;
        boolean checking;
    }

    static final class HttpProbe implements Probe {
        private int connectTimeout;
        private HttpClient client;
        @Override public Result check(URI endpoint, int connectMillis, int requestMillis) throws Exception {
            HttpClient http;
            synchronized (this) {
                if (client == null || connectTimeout != connectMillis) {
                    connectTimeout = connectMillis;
                    client = MathVisionModelEndpoint.httpClient(connectMillis);
                }
                http = client;
            }
            // GET on the generation endpoint carries no API key, prompt, or inference body.
            // 401/404/405 still prove network reachability; /models is not required.
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(requestMillis))
                    .header("Accept", "application/json").GET().build();
            CompletableFuture<HttpResponse<String>> response = http.sendAsync(request, ignored -> new LimitedBody());
            try {
                HttpResponse<String> result = response.get(requestMillis, TimeUnit.MILLISECONDS);
                return classify(result.statusCode(), result.body());
            } catch (InterruptedException | TimeoutException failure) {
                response.cancel(true);
                throw failure;
            }
        }
    }

    /** Bound custom gateway response bodies and finish without waiting for an unlimited stream. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<String> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int length = Math.min(buffer.remaining(), 4096 - bytes.size());
                byte[] chunk = new byte[length]; buffer.get(chunk); bytes.write(chunk, 0, length);
                if (bytes.size() == 4096) { body.complete(bytes.toString(StandardCharsets.UTF_8)); subscription.cancel(); return; }
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toString(StandardCharsets.UTF_8)); }
    }
}
