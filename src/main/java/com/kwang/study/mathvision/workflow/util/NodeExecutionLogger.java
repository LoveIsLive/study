package com.kwang.study.mathvision.workflow.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/**
 * Records one concise lifecycle log for every workflow node invocation.
 *
 * <p>The active scope is also used by {@code MathVisionAiChatService} to count
 * logical model requests and real outbound HTTP attempts, including provider
 * fallback and retry attempts that are otherwise hidden inside one node call.</p>
 */
public final class NodeExecutionLogger {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutionLogger.class);
    private static final ThreadLocal<Deque<Metrics>> ACTIVE =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<StageScope> STAGE = new ThreadLocal<>();
    private static final ScheduledThreadPoolExecutor PROGRESS = progressExecutor();
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(\\b(?:api[_-]?key|x-api-key|service[_-]?secret|client[_-]?secret|access[_-]?token|refresh[_-]?token|password|secret|token|authorization)\\b[\"']?\\s*[:=]\\s*[\"']?)(?:Bearer\\s+)?[^\\s,\"';]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*");

    private NodeExecutionLogger() {
    }

    public static <T> T execute(Long taskId,
                                String stage,
                                String node,
                                Supplier<T> action,
                                ToIntFunction<T> resultApiCalls) {
        return execute(taskId, stage, node, "-", action, resultApiCalls);
    }

    public static <T> T execute(Long taskId,
                                String stage,
                                String node,
                                String invocation,
                                Supplier<T> action,
                                ToIntFunction<T> resultApiCalls) {
        StageScope scope = STAGE.get();
        String resolvedInvocation = invocation == null || invocation.isBlank() ? "-" : invocation;
        Metrics metrics = new Metrics("taskId=" + taskId + ", stage=" + stage + ", node=" + node
                + ", invocation=" + resolvedInvocation + (scope == null ? "" : scope.executionContext()));
        Deque<Metrics> stack = ACTIVE.get();
        stack.push(metrics);
        if (scope != null) {
            scope.nodes.incrementAndGet();
            scope.activeNodes.incrementAndGet();
        }
        log.info("MathVision Node 开始, {}", metrics.context);
        ScheduledFuture<?> progress = PROGRESS.scheduleWithFixedDelay(metrics::logProgress, 30, 30, TimeUnit.SECONDS);
        try {
            T result = action.get();
            int reportedApiCalls = resultApiCalls != null && result != null
                    ? Math.max(resultApiCalls.applyAsInt(result), 0)
                    : 0;
            metrics.finish();
            log.info("MathVision Node 完成, {}, "
                            + "durationMs={}, apiCalls={}, logicalRequests={}, resultApiCalls={}",
                    metrics.context,
                    elapsedMillis(metrics.startedNanos),
                    metrics.httpAttempts.get(),
                    metrics.logicalRequests.get(),
                    reportedApiCalls);
            return result;
        } catch (RuntimeException | Error e) {
            metrics.finish();
            log.warn("MathVision Node 失败, {}, "
                            + "durationMs={}, apiCalls={}, logicalRequests={}, errorType={}, error={}",
                    metrics.context,
                    elapsedMillis(metrics.startedNanos),
                    metrics.httpAttempts.get(),
                    metrics.logicalRequests.get(),
                    e.getClass().getSimpleName(),
                    summarize(e.getMessage()));
            throw e;
        } finally {
            metrics.finish();
            progress.cancel(false);
            stack.pop();
            if (scope != null) scope.activeNodes.decrementAndGet();
            if (stack.isEmpty()) {
                ACTIVE.remove();
            }
        }
    }

    public static void recordLogicalRequest() {
        Metrics metrics = currentMetrics();
        if (metrics != null) {
            metrics.logicalRequests.incrementAndGet();
        }
        StageScope scope = STAGE.get();
        if (scope != null) scope.logicalRequests.incrementAndGet();
    }

    public static void recordHttpAttempt() {
        Metrics metrics = currentMetrics();
        if (metrics != null) {
            metrics.httpAttempts.incrementAndGet();
        }
        StageScope scope = STAGE.get();
        if (scope != null) scope.httpAttempts.incrementAndGet();
    }

    /** Updates the operation shown by the node's periodic progress log. No prompt or credential belongs here. */
    public static void progress(String operation) {
        Metrics metrics = currentMetrics();
        if (metrics != null) metrics.operation = summarize(operation);
        else if (STAGE.get() != null) STAGE.get().operation = summarize(operation);
    }

    public static String context() {
        Metrics metrics = currentMetrics();
        StageScope scope = STAGE.get();
        return metrics != null ? metrics.context : scope != null ? scope.context : "-";
    }

    public static StageScope stage(Long taskId, String stage, Integer version, String token) {
        return new StageScope(taskId, stage, version, token);
    }

    // Kept separate from the timer so progress and scope isolation can be verified without sleeping.
    static void reportProgress() {
        Metrics metrics = currentMetrics();
        if (metrics != null) metrics.logProgress();
        else if (STAGE.get() != null) STAGE.get().logProgress();
    }

    private static ScheduledThreadPoolExecutor progressExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, action -> {
            Thread thread = new Thread(action, "mathvision-node-progress");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static Metrics currentMetrics() {
        Deque<Metrics> stack = ACTIVE.get();
        if (stack.isEmpty()) {
            ACTIVE.remove();
            return null;
        }
        return stack.peek();
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max((System.nanoTime() - startedNanos) / 1_000_000L, 0L);
    }

    public static String summarize(String message) {
        if (message == null || message.isBlank()) {
            return "-";
        }
        String singleLine = message.replace('\r', ' ').replace('\n', ' ').trim();
        singleLine = CREDENTIAL.matcher(singleLine).replaceAll("$1<redacted>");
        singleLine = BEARER.matcher(singleLine).replaceAll("Bearer <redacted>");
        return singleLine.length() <= 500 ? singleLine : singleLine.substring(0, 500) + "...";
    }

    private static final class Metrics {
        private final String context;
        private final long startedNanos = System.nanoTime();
        private final AtomicInteger logicalRequests = new AtomicInteger();
        private final AtomicInteger httpAttempts = new AtomicInteger();
        private volatile String operation = "执行节点逻辑";
        private boolean finished;

        private Metrics(String context) { this.context = context; }

        private synchronized void logProgress() {
            if (!finished) log.info("MathVision Node 执行中, {}, operation={}, durationMs={}, apiCalls={}, logicalRequests={}",
                    context, operation, elapsedMillis(startedNanos), httpAttempts.get(), logicalRequests.get());
        }

        private synchronized void finish() { finished = true; }
    }

    /** Per-execution totals, restored on close so pooled worker threads cannot leak task context. */
    public static final class StageScope implements AutoCloseable {
        private final StageScope previous = STAGE.get();
        private final long startedNanos = System.nanoTime();
        private final AtomicInteger httpAttempts = new AtomicInteger();
        private final AtomicInteger logicalRequests = new AtomicInteger();
        private final AtomicInteger nodes = new AtomicInteger();
        private final AtomicInteger activeNodes = new AtomicInteger();
        private final String executionContext;
        private final String context;
        private final ScheduledFuture<?> progress;
        private volatile String operation = "读取阶段上下文";
        private boolean closed;

        private StageScope(Long taskId, String stage, Integer version, String token) {
            executionContext = ", version=" + version + ", execution="
                    + (token == null || token.isBlank() ? "-" : token.substring(0, Math.min(8, token.length())));
            context = "taskId=" + taskId + ", stage=" + stage + executionContext;
            STAGE.set(this);
            progress = PROGRESS.scheduleWithFixedDelay(this::logProgress, 30, 30, TimeUnit.SECONDS);
        }

        private String executionContext() { return executionContext; }

        public String summary() {
            return context + ", durationMs=" + elapsedMillis(startedNanos) + ", nodes=" + nodes.get()
                    + ", apiCalls=" + httpAttempts.get() + ", logicalRequests=" + logicalRequests.get();
        }

        private synchronized void logProgress() {
            if (!closed && activeNodes.get() == 0) log.info("MathVision 阶段执行中, {}, operation={}", summary(), operation);
        }

        @Override public synchronized void close() {
            closed = true;
            progress.cancel(false);
            if (previous == null) STAGE.remove(); else STAGE.set(previous);
        }
    }
}
