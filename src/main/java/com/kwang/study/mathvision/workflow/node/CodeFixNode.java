package com.kwang.study.mathvision.workflow.node;

import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.ai.MathVisionAiChatService;
import com.kwang.study.mathvision.workflow.model.AiContentPart;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.model.CodeFixRequest;
import com.kwang.study.mathvision.workflow.model.CodeFixResult;
import com.kwang.study.mathvision.workflow.prompt.CodeFixPrompts;
import com.kwang.study.mathvision.workflow.prompt.ToolSchemas;
import com.kwang.study.mathvision.workflow.util.GeoGebraCodeUtils;
import com.kwang.study.mathvision.workflow.util.ManimCodeUtils;
import com.kwang.study.mathvision.workflow.util.TextHealthDiagnostics;
import com.kwang.study.mathvision.workflow.util.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class CodeFixNode {

    private static final Logger log = LoggerFactory.getLogger(CodeFixNode.class);
    private static final String RATE_LIMIT_BLOCKED_REASON =
            "Provider rate limit exhausted after configured retries";

    private final MathVisionAiChatService aiChatService;

    public CodeFixNode(MathVisionAiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    public Result run(MathVisionTask task,
                      CodeFixRequest request,
                      MathVisionStageExecutionContext context) {
        return run(task, request, context, List.of());
    }

    public Result run(MathVisionTask task,
                      CodeFixRequest request,
                      MathVisionStageExecutionContext context,
                      List<AiMessage> conversationHistory) {
        if (context != null) {
            context.checkCanceled();
        }
        Instant start = Instant.now();
        CodeFixResult result = new CodeFixResult();
        if (request == null) {
            result.setOutcome(CodeFixResult.FixOutcome.FAILED);
            result.setFailureReason("No code fix request available");
            return finish(task, result, 0, start);
        }
        result.setSource(request.getSource());
        result.setOriginalGeneratedCode(request.getGeneratedCode());
        result.setErrorReason(request.getErrorReason());

        if (!StringUtils.hasText(request.getGeneratedCode())) {
            result.setOutcome(CodeFixResult.FixOutcome.FAILED);
            result.setFailureReason("No code provided for code fix");
            return finish(task, result, 0, start);
        }

        String outputTarget = StringUtils.hasText(request.getOutputTarget()) ? request.getOutputTarget() : "manim";
        String rulesPrompt = CodeFixPrompts.buildRulesPrompt(request);
        String fixedContextPrompt = CodeFixPrompts.buildFixedContextPrompt(request);
        String currentRequestPrompt = CodeFixPrompts.buildCurrentRequestPrompt(request);
        result.setRulesPrompt(rulesPrompt);
        result.setFixedContextPrompt(fixedContextPrompt);
        result.setCurrentRequestPrompt(currentRequestPrompt);
        log.info("MathVision CodeFix request prepared, taskId={}, source={}, outputTarget={}, codeLines={}, "
                        + "rulesTokens~{}, fixedContextTokens~{}, currentRequestTokens~{}, errorSignature={}",
                task != null ? task.getId() : null,
                request.getSource(),
                outputTarget,
                countNonBlankLines(request.getGeneratedCode()),
                TokenEstimator.estimateTokens(rulesPrompt),
                TokenEstimator.estimateTokens(fixedContextPrompt),
                TokenEstimator.estimateTokens(currentRequestPrompt),
                abbreviate(com.kwang.study.mathvision.workflow.util.ErrorSummarizer
                        .summarizeSignature(request.getErrorReason()), 240));
        MathVisionAiChatService.CodeResponse response;
        try {
            List<AiMessage> messages = new ArrayList<>();
            messages.add(AiMessage.system(rulesPrompt));
            messages.add(AiMessage.system(fixedContextPrompt));
            if (conversationHistory != null) {
                messages.addAll(conversationHistory);
            }
            messages.add(AiMessage.user(List.of(AiContentPart.text(currentRequestPrompt))));
            response = aiChatService.requestCode(
                    task,
                    messages,
                    "geogebra".equalsIgnoreCase(outputTarget) ? ToolSchemas.GEOGEBRA_CODE : ToolSchemas.MANIM_CODE,
                    preferredFields(outputTarget));
        } catch (Exception e) {
            if (isRateLimitFailure(e)) {
                result.setOutcome(CodeFixResult.FixOutcome.RATE_LIMIT_BLOCKED);
                result.setFailureReason(RATE_LIMIT_BLOCKED_REASON);
            } else {
                result.setOutcome(CodeFixResult.FixOutcome.FAILED);
                result.setFailureReason("Code fix request failed: " + e.getMessage());
            }
            return finish(task, result, 0, start);
        }

        String fixedCode = response.getCode();
        result.setToolCalls(response.getApiCalls());
        log.info("MathVision CodeFix provider response received, taskId={}, source={}, apiCalls={}, hasCode={}, "
                        + "failureReason={}",
                task != null ? task.getId() : null,
                request.getSource(),
                response.getApiCalls(),
                StringUtils.hasText(fixedCode),
                abbreviate(response.getFailureReason(), 500));
        if (!StringUtils.hasText(fixedCode)) {
            if (isRateLimitFailure(response.getFailureReason())) {
                result.setOutcome(CodeFixResult.FixOutcome.RATE_LIMIT_BLOCKED);
                result.setFailureReason(RATE_LIMIT_BLOCKED_REASON);
            } else {
                result.setOutcome(CodeFixResult.FixOutcome.UNCHANGED);
                result.setFailureReason(StringUtils.hasText(response.getFailureReason())
                        ? response.getFailureReason()
                        : "Code fix returned no parseable code");
            }
            return finish(task, result, response.getApiCalls(), start, response.getAssistantText());
        }

        fixedCode = normalizeFixedCode(fixedCode, outputTarget);
        if (!hasCodeChanged(request.getGeneratedCode(), fixedCode)) {
            result.setOutcome(CodeFixResult.FixOutcome.UNCHANGED);
            result.setFailureReason("Code fix returned code identical to source code");
            return finish(task, result, response.getApiCalls(), start, response.getAssistantText());
        }

        result.setApplied(true);
        result.setFixedGeneratedCode(fixedCode);

        if (StringUtils.hasText(request.getInputTextHealth())
                && TextHealthDiagnostics.hasSuspiciousEncoding(request.getInputTextHealth())) {
            result.setOutcome(CodeFixResult.FixOutcome.INPUT_CORRUPTED);
        }

        List<String> staticIssues = "geogebra".equalsIgnoreCase(outputTarget)
                ? GeoGebraCodeUtils.validateFull(fixedCode)
                : ManimCodeUtils.validateFull(fixedCode);
        result.setPostFixStaticAuditIssueCount(staticIssues.size());
        result.setPostFixStaticAuditSummary("geogebra".equalsIgnoreCase(outputTarget)
                ? GeoGebraCodeUtils.summarizeIssues(staticIssues)
                : ManimCodeUtils.summarizeIssues(staticIssues));
        if (staticIssues.isEmpty()) {
            result.setOutcome(CodeFixResult.FixOutcome.FIXED);
        } else if (!"geogebra".equalsIgnoreCase(outputTarget)
                && ManimCodeUtils.hasCoordinateScaleContractViolation(staticIssues)) {
            result.setApplied(false);
            result.setFailureReason("Code fix rejected because it violates the Manim coordinate scale contract: "
                    + result.getPostFixStaticAuditSummary());
            result.setOutcome(CodeFixResult.FixOutcome.FAILED);
        } else {
            result.setOutcome(CodeFixResult.FixOutcome.APPLIED_WITH_ISSUES);
        }
        result.setExecutionTimeSeconds(secondsSince(start));

        log.info("MathVision 共享代码修复完成, taskId={}, source={}, outcome={}, applied={}, staticIssues={}",
                task.getId(), request.getSource(), result.getOutcome(), result.isApplied(), staticIssues.size());
        return finish(task, result, response.getApiCalls(), start, response.getAssistantText());
    }

    private Result finish(MathVisionTask task, CodeFixResult result, int apiCalls, Instant start) {
        return finish(task, result, apiCalls, start, "");
    }

    private Result finish(MathVisionTask task,
                          CodeFixResult result,
                          int apiCalls,
                          Instant start,
                          String assistantTranscript) {
        result.setExecutionTimeSeconds(secondsSince(start));
        Long taskId = task != null ? task.getId() : null;
        if (result.isApplied()) {
            log.info("MathVision shared code fix completed, taskId={}, source={}, outcome={}, applied={}, staticIssues={}",
                    taskId, result.getSource(), result.getOutcome(), true,
                    result.getPostFixStaticAuditIssueCount());
        } else {
            log.warn("MathVision shared code fix not applied, taskId={}, source={}, outcome={}, reason={}",
                    taskId, result.getSource(), result.getOutcome(), result.getFailureReason());
        }
        return new Result(result, apiCalls, assistantTranscript);
    }

    private List<String> preferredFields(String outputTarget) {
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return List.of("geogebraCode");
        }
        return List.of("manimCode");
    }

    private String normalizeFixedCode(String fixedCode, String outputTarget) {
        if (!StringUtils.hasText(fixedCode)) {
            return "";
        }
        String normalized = fixedCode.replace("\r\n", "\n").replace('\r', '\n').trim();
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            normalized = GeoGebraCodeUtils.ensureDefaultViewCommand(
                    GeoGebraCodeUtils.extractCode(normalized));
        } else {
            normalized = ManimCodeUtils.enforceMainSceneName(ManimCodeUtils.extractCode(normalized));
        }
        return normalized;
    }

    private boolean hasCodeChanged(String original, String fixed) {
        String left = original == null ? "" : original.replace("\r\n", "\n").replace('\r', '\n').trim();
        String right = fixed == null ? "" : fixed.replace("\r\n", "\n").replace('\r', '\n').trim();
        return !left.equals(right);
    }

    private boolean isRateLimitFailure(Throwable error) {
        return error != null && isRateLimitFailure(error.getMessage());
    }

    private boolean isRateLimitFailure(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("rate limit")
                || normalized.contains("429")
                || normalized.contains("too many requests")
                || normalized.contains("retry-after");
    }

    private int countNonBlankLines(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        int count = 0;
        for (String line : text.split("\\R", -1)) {
            if (StringUtils.hasText(line)) {
                count++;
            }
        }
        return count;
    }

    private String abbreviate(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private double secondsSince(Instant start) {
        return Duration.between(start, Instant.now()).toMillis() / 1000.0D;
    }

    public static final class Result {
        private final CodeFixResult fixResult;
        private final int apiCalls;
        private final String assistantTranscript;

        private Result(CodeFixResult fixResult, int apiCalls) {
            this(fixResult, apiCalls, "");
        }

        private Result(CodeFixResult fixResult, int apiCalls, String assistantTranscript) {
            this.fixResult = fixResult;
            this.apiCalls = apiCalls;
            this.assistantTranscript = assistantTranscript == null ? "" : assistantTranscript;
        }

        public CodeFixResult getFixResult() {
            return fixResult;
        }

        public int getApiCalls() {
            return apiCalls;
        }

        public String getAssistantTranscript() {
            return assistantTranscript;
        }
    }
}
