package com.kwang.study.mathvision.workflow.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeExecutionLoggerTest {

    private static final String LOGGER_NAME = NodeExecutionLogger.class.getName();

    private LoggerContext loggerContext;
    private Configuration configuration;
    private MemoryAppender appender;

    @BeforeEach
    void attachAppender() {
        loggerContext = (LoggerContext) LogManager.getContext(false);
        configuration = loggerContext.getConfiguration();
        appender = new MemoryAppender();
        appender.start();
        configuration.addAppender(appender);
        LoggerConfig loggerConfig = new LoggerConfig(LOGGER_NAME, Level.INFO, false);
        loggerConfig.addAppender(appender, Level.INFO, null);
        configuration.addLogger(LOGGER_NAME, loggerConfig);
        loggerContext.updateLoggers();
    }

    @AfterEach
    void detachAppender() {
        configuration.removeLogger(LOGGER_NAME);
        configuration.getAppenders().remove(appender.getName());
        appender.stop();
        loggerContext.updateLoggers();
    }

    @Test
    void logsDurationAndAllApiCountersOnSuccess() {
        String result = NodeExecutionLogger.execute(
                42L,
                "visual_storyboard",
                "VisualDesignNode",
                "attempt=1",
                () -> {
                    NodeExecutionLogger.recordLogicalRequest();
                    NodeExecutionLogger.recordHttpAttempt();
                    NodeExecutionLogger.recordHttpAttempt();
                    return "ok";
                },
                ignored -> 1);

        assertThat(result).isEqualTo("ok");
        assertThat(appender.joinedMessages())
                .contains("MathVision Node 开始")
                .contains("taskId=42")
                .contains("stage=visual_storyboard")
                .contains("node=VisualDesignNode")
                .contains("invocation=attempt=1")
                .contains("MathVision Node 完成")
                .contains("durationMs=")
                .contains("apiCalls=2")
                .contains("logicalRequests=1")
                .contains("resultApiCalls=1");
    }

    @Test
    void logsElapsedCountersWhenNodeFails() {
        assertThatThrownBy(() -> NodeExecutionLogger.execute(
                43L,
                "visual_storyboard",
                "StoryboardValidationNode",
                () -> {
                    NodeExecutionLogger.recordLogicalRequest();
                    NodeExecutionLogger.recordHttpAttempt();
                    throw new IllegalStateException("validation failed\nwith detail");
                },
                ignored -> 0))
                .isInstanceOf(IllegalStateException.class);

        assertThat(appender.joinedMessages())
                .contains("MathVision Node 失败")
                .contains("taskId=43")
                .contains("node=StoryboardValidationNode")
                .contains("durationMs=")
                .contains("apiCalls=1")
                .contains("logicalRequests=1")
                .contains("errorType=IllegalStateException")
                .contains("error=validation failed with detail");
    }

    @Test
    void reportsProgressAndCountsActualRequestsAcrossNodesWithoutLeakingStageContext() {
        try (NodeExecutionLogger.StageScope stage = NodeExecutionLogger.stage(44L, "render_result", 3, "12345678-private-token")) {
            NodeExecutionLogger.execute(44L, "render_result", "CodeFixNode", () -> {
                NodeExecutionLogger.recordLogicalRequest();
                NodeExecutionLogger.recordHttpAttempt();
                NodeExecutionLogger.recordHttpAttempt();
                NodeExecutionLogger.progress("等待模型响应");
                NodeExecutionLogger.reportProgress();
                return "ok";
            }, ignored -> 1);
            NodeExecutionLogger.execute(44L, "render_result", "RenderNode", () -> "ok", ignored -> 0);
            assertThat(stage.summary()).contains("nodes=2", "apiCalls=2", "logicalRequests=1");
            NodeExecutionLogger.progress("等待主服务归档最终产物");
            NodeExecutionLogger.reportProgress();
            assertThat(appender.joinedMessages()).contains("阶段执行中", "operation=等待主服务归档最终产物");
        }

        assertThat(NodeExecutionLogger.context()).isEqualTo("-");
        String before = appender.joinedMessages();
        NodeExecutionLogger.reportProgress();
        assertThat(appender.joinedMessages()).isEqualTo(before)
                .contains("Node 执行中", "operation=等待模型响应", "version=3", "execution=12345678")
                .doesNotContain("private-token");
        try (NodeExecutionLogger.StageScope next = NodeExecutionLogger.stage(45L, "problem_normalization", 1, "abcdefgh")) {
            assertThat(next.summary()).contains("nodes=0", "apiCalls=0", "logicalRequests=0");
        }
    }

    @Test
    void cleansNodeAndStageContextAfterFailure() {
        assertThatThrownBy(() -> {
            try (NodeExecutionLogger.StageScope stage = NodeExecutionLogger.stage(46L, "visual_storyboard", 1, "abcdefgh")) {
                NodeExecutionLogger.execute(46L, "visual_storyboard", "VisualDesignNode", () -> {
                    NodeExecutionLogger.recordHttpAttempt();
                    throw new IllegalStateException("failed");
                }, ignored -> 0);
            }
        }).isInstanceOf(IllegalStateException.class);
        assertThat(NodeExecutionLogger.context()).isEqualTo("-");
        String before = appender.joinedMessages();
        NodeExecutionLogger.reportProgress();
        assertThat(appender.joinedMessages()).isEqualTo(before);
    }

    @Test
    void isolatesConcurrentWorkerExecutions() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        try {
            Future<String> first = workers.submit(() -> concurrentExecution(47L, "first-run", 1, ready));
            Future<String> second = workers.submit(() -> concurrentExecution(48L, "other-run", 2, ready));
            assertThat(first.get(5, TimeUnit.SECONDS)).contains("taskId=47", "execution=first-ru", "apiCalls=1");
            assertThat(second.get(5, TimeUnit.SECONDS)).contains("taskId=48", "execution=other-ru", "apiCalls=2");
        } finally {
            workers.shutdownNow();
        }
    }

    private String concurrentExecution(Long taskId, String token, int attempts, CountDownLatch ready) {
        try (NodeExecutionLogger.StageScope stage = NodeExecutionLogger.stage(taskId, "code_generation", 1, token)) {
            NodeExecutionLogger.execute(taskId, "code_generation", "CodeGenerationNode", () -> {
                ready.countDown();
                try {
                    if (!ready.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Worker did not start");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                for (int i = 0; i < attempts; i++) NodeExecutionLogger.recordHttpAttempt();
                NodeExecutionLogger.reportProgress();
                assertThat(NodeExecutionLogger.context()).contains("taskId=" + taskId);
                return "ok";
            }, ignored -> 1);
            return stage.summary();
        }
    }

    @Test
    void redactsCredentialsWhileKeepingTheFailureReason() {
        assertThat(NodeExecutionLogger.summarize(
                "HTTP 401\nAuthorization: Bearer private-key, api_key=another-key, "
                        + "serviceSecret=secret-value, service-secret=other-secret, access_token=token-value"))
                .contains("HTTP 401", "<redacted>")
                .doesNotContain("private-key", "another-key", "secret-value", "other-secret", "token-value", "\n");
    }

    private static final class MemoryAppender extends AbstractAppender {

        private final List<String> messages = new ArrayList<>();

        private MemoryAppender() {
            super(
                    "NodeExecutionLoggerTestAppender",
                    (Filter) null,
                    (Layout<? extends Serializable>) PatternLayout.createDefaultLayout(),
                    true,
                    Property.EMPTY_ARRAY);
        }

        @Override
        public synchronized void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }

        private synchronized String joinedMessages() {
            return String.join("\n", messages);
        }
    }
}
