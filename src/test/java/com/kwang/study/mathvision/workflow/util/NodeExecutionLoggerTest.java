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
