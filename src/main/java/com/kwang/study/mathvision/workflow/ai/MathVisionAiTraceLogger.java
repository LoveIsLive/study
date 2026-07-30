package com.kwang.study.mathvision.workflow.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dedicated, opt-in trace channel for complete MathVision AI request/response diagnosis. */
final class MathVisionAiTraceLogger {

    private static final Logger TRACE = LoggerFactory.getLogger("com.kwang.study.mathvision.ai.trace");
    private static final Pattern LARGE_DATA_FIELD = Pattern.compile(
            "(\\\"(?:data|url)\\\"\\s*:\\s*\\\")([^\\\"]{1024,})(\\\")");

    private MathVisionAiTraceLogger() {
    }

    static void logRequest(Long taskId,
                           String provider,
                           String model,
                           String url,
                           int attempt,
                           int timeoutSeconds,
                           String body,
                           int maxChars) {
        TRACE.debug("AI request taskId={}, provider={}, model={}, attempt={}, timeoutSeconds={}, url={}, bodyChars={}\n{}",
                taskId, provider, model, attempt, timeoutSeconds, url,
                body != null ? body.length() : 0,
                sanitizeAndLimit(body, maxChars));
    }

    static void logResponse(Long taskId,
                            String provider,
                            String model,
                            int attempt,
                            int statusCode,
                            String body,
                            int maxChars) {
        TRACE.debug("AI response taskId={}, provider={}, model={}, attempt={}, http={}, bodyChars={}\n{}",
                taskId, provider, model, attempt, statusCode,
                body != null ? body.length() : 0,
                sanitizeAndLimit(body, maxChars));
    }

    static void logFailure(Long taskId,
                           String provider,
                           String model,
                           int attempt,
                           Throwable error) {
        TRACE.debug("AI failure taskId={}, provider={}, model={}, attempt={}, type={}, message={}",
                taskId, provider, model, attempt,
                error != null ? error.getClass().getName() : "unknown",
                error != null ? error.getMessage() : "unknown");
    }

    private static String sanitizeAndLimit(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        Matcher matcher = LARGE_DATA_FIELD.matcher(value);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(1)
                    + "<large-data-redacted chars=" + matcher.group(2).length() + ">"
                    + matcher.group(3);
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sanitized);
        int limit = Math.max(maxChars, 1_000);
        if (sanitized.length() <= limit) {
            return sanitized.toString();
        }
        return sanitized.substring(0, limit)
                + "\n<MathVision AI trace truncated, original chars=" + sanitized.length() + ">";
    }
}
