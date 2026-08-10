package com.kwang.study.mathvision.workflow.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

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
        Metrics metrics = new Metrics();
        Deque<Metrics> stack = ACTIVE.get();
        stack.push(metrics);
        long startedNanos = System.nanoTime();
        String resolvedInvocation = invocation == null || invocation.isBlank() ? "-" : invocation;
        log.info("MathVision Node 开始, taskId={}, stage={}, node={}, invocation={}",
                taskId, stage, node, resolvedInvocation);
        try {
            T result = action.get();
            int reportedApiCalls = resultApiCalls != null && result != null
                    ? Math.max(resultApiCalls.applyAsInt(result), 0)
                    : 0;
            log.info("MathVision Node 完成, taskId={}, stage={}, node={}, invocation={}, "
                            + "durationMs={}, apiCalls={}, logicalRequests={}, resultApiCalls={}",
                    taskId,
                    stage,
                    node,
                    resolvedInvocation,
                    elapsedMillis(startedNanos),
                    metrics.httpAttempts,
                    metrics.logicalRequests,
                    reportedApiCalls);
            return result;
        } catch (RuntimeException | Error e) {
            log.warn("MathVision Node 失败, taskId={}, stage={}, node={}, invocation={}, "
                            + "durationMs={}, apiCalls={}, logicalRequests={}, errorType={}, error={}",
                    taskId,
                    stage,
                    node,
                    resolvedInvocation,
                    elapsedMillis(startedNanos),
                    metrics.httpAttempts,
                    metrics.logicalRequests,
                    e.getClass().getSimpleName(),
                    summarize(e.getMessage()));
            throw e;
        } finally {
            stack.pop();
            if (stack.isEmpty()) {
                ACTIVE.remove();
            }
        }
    }

    public static void recordLogicalRequest() {
        Metrics metrics = currentMetrics();
        if (metrics != null) {
            metrics.logicalRequests++;
        }
    }

    public static void recordHttpAttempt() {
        Metrics metrics = currentMetrics();
        if (metrics != null) {
            metrics.httpAttempts++;
        }
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

    private static String summarize(String message) {
        if (message == null || message.isBlank()) {
            return "-";
        }
        String singleLine = message.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= 500 ? singleLine : singleLine.substring(0, 500) + "...";
    }

    private static final class Metrics {
        private int logicalRequests;
        private int httpAttempts;
    }
}
