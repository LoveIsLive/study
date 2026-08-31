package com.kwang.study.mathvision.workflow.node;

import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.model.CodeFixResult;
import com.kwang.study.mathvision.workflow.model.CodeResult;
import com.kwang.study.mathvision.workflow.model.Narrative;
import com.kwang.study.mathvision.workflow.model.RenderResult;
import com.kwang.study.mathvision.workflow.render.GeoGebraRenderService;
import com.kwang.study.mathvision.workflow.render.ManimRenderService;
import com.kwang.study.mathvision.workflow.util.ErrorSummarizer;
import com.kwang.study.mathvision.workflow.util.GeoGebraCodeUtils;
import com.kwang.study.mathvision.workflow.util.ManimCodeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class RenderNode {

    private static final Logger log = LoggerFactory.getLogger(RenderNode.class);
    public static final int DEFAULT_MAX_RENDER_RETRIES = 10;

    private final ManimRenderService manimRenderService;
    private final GeoGebraRenderService geoGebraRenderService;

    public RenderNode(ManimRenderService manimRenderService,
                      GeoGebraRenderService geoGebraRenderService) {
        this.manimRenderService = manimRenderService;
        this.geoGebraRenderService = geoGebraRenderService;
    }

    public Result run(MathVisionTask task,
                      CodeResult codeResult,
                      Narrative narrative,
                      RenderRetryState retryState,
                      String renderQuality,
                      int maxRenderRetries,
                      Path outputDir,
                      MathVisionStageExecutionContext context) {
        if (context != null) {
            context.checkCanceled();
        }
        RenderRetryState effectiveRetryState = retryState != null ? retryState : new RenderRetryState();
        effectiveRetryState.prepareAttempt();
        RenderResult renderResult = codeResult.isGeoGebraTarget()
                ? renderGeoGebraPreview(codeResult, narrative, effectiveRetryState, maxRenderRetries, outputDir, context)
                : renderManim(codeResult, effectiveRetryState, renderQuality, maxRenderRetries, outputDir);
        if (context != null) {
            context.checkCanceled();
        }
        log.debug("MathVision RenderNode 执行完成, taskId={}, success={}, artifactPath={}",
                task.getId(), renderResult.isSuccess(), renderResult.getArtifactPath());
        return new Result(renderResult, 0);
    }

    private RenderResult renderManim(CodeResult codeResult,
                                     RenderRetryState retryState,
                                     String renderQuality,
                                     int maxRenderRetries,
                                     Path outputDir) {
        Instant start = Instant.now();
        String currentCode = ManimCodeUtils.migrateLegacyGttsService(
                ManimCodeUtils.enforceMainSceneName(
                        ManimCodeUtils.extractCode(codeResult.getGeneratedCode())));
        codeResult.setGeneratedCode(currentCode);
        String sceneName = ManimCodeUtils.EXPECTED_SCENE_NAME;
        codeResult.setSceneName(sceneName);
        int attemptNumber = retryState.getAttempts() + 1;
        List<String> preflightIssues = ManimCodeUtils.validateRenderBlockers(currentCode);
        if (!preflightIssues.isEmpty() && canRequestFix(attemptNumber, maxRenderRetries)) {
            retryState.setAttempts(attemptNumber);
            retryState.setRequestFix(true);
            retryState.setPendingFocusedError(ErrorSummarizer.buildRenderFixSummary(String.join("\n", preflightIssues)));
            retryState.setPendingStaticAuditIssues(preflightIssues);
            log.warn("MathVision RenderNode preflight found {} Manim issues before execution: {}",
                    preflightIssues.size(), retryState.getPendingFocusedError());
            return failureResult(codeResult, "manim", "mp4", sceneName, currentCode, attemptNumber,
                    retryState.getPendingFocusedError(), null, retryState.getFixToolCalls(), start);
        }

        ManimRenderService.Attempt attempt = manimRenderService.render(
                currentCode, sceneName, renderQuality, outputDir);
        RenderResult result = baseResult(codeResult, "manim", "mp4", sceneName);
        result.setSuccess(attempt.isSuccess());
        result.setFinalGeneratedCode(currentCode);
        result.setAttempts(attemptNumber);
        result.setToolCalls(retryState.getFixToolCalls());
        result.setExecutionTimeSeconds(attempt.getExecutionTimeSeconds());
        result.setGeometryPath(attempt.getGeometryPath());
        if (attempt.isSuccess()) {
            retryState.reset();
            result.setVideoPath(attempt.getVideoPath());
            result.setArtifactPath(attempt.getVideoPath());
        } else {
            retryState.setAttempts(attemptNumber);
            String lastError = ErrorSummarizer.buildDisplayError(attempt.getStdout(), attempt.getStderr());
            if (!StringUtils.hasText(lastError)) {
                lastError = "Render failed";
            }
            result.setLastError(lastError);
            if (attempt.isTimedOut()) {
                prepareTimeoutFixIfPossible(retryState, attempt, attemptNumber, currentCode, maxRenderRetries);
            } else if (canRequestFix(attemptNumber, maxRenderRetries)
                    && !ErrorSummarizer.isEnvironmentError(lastError)) {
                retryState.setRequestFix(true);
                retryState.setPendingFocusedError(ErrorSummarizer.buildFullTracebackRenderFixContext(
                        attempt.getStdout(), attempt.getStderr()));
                if (!StringUtils.hasText(retryState.getPendingFocusedError())) {
                    retryState.setPendingFocusedError(ErrorSummarizer.extractFocusedError(
                            attempt.getStdout(), attempt.getStderr()));
                }
                retryState.setPendingStaticAuditIssues(ManimCodeUtils.validateFull(currentCode));
            }
        }
        return result;
    }

    private void prepareTimeoutFixIfPossible(RenderRetryState retryState,
                                             ManimRenderService.Attempt attempt,
                                             int attemptNumber,
                                             String currentCode,
                                             int maxRenderRetries) {
        String stderr = attempt.getStderr() != null ? attempt.getStderr() : "";
        if (!stderr.contains("Traceback (most recent call last)")
                || !canRequestFix(attemptNumber, maxRenderRetries)) {
            return;
        }
        String focusedError = ErrorSummarizer.extractFocusedError(attempt.getStdout(), attempt.getStderr());
        if (ErrorSummarizer.isEnvironmentError(focusedError)) {
            return;
        }
        retryState.setRequestFix(true);
        retryState.setPendingFocusedError(ErrorSummarizer.buildFullTracebackRenderFixContext(
                attempt.getStdout(), attempt.getStderr()));
        retryState.setPendingStaticAuditIssues(ManimCodeUtils.validateFull(currentCode));
    }

    private RenderResult renderGeoGebraPreview(CodeResult codeResult,
                                               Narrative narrative,
                                               RenderRetryState retryState,
                                               int maxRenderRetries,
                                               Path outputDir,
                                               MathVisionStageExecutionContext context) {
        Instant start = Instant.now();
        String currentCode = GeoGebraCodeUtils.enrichWithSceneButtons(
                codeResult.getGeneratedCode(),
                narrative != null ? narrative.getStoryboard() : null);
        codeResult.setGeneratedCode(currentCode);
        String sceneName = StringUtils.hasText(codeResult.getSceneName())
                ? codeResult.getSceneName()
                : GeoGebraCodeUtils.EXPECTED_FIGURE_NAME;
        codeResult.setSceneName(sceneName);
        int attemptNumber = retryState.getAttempts() + 1;
        RenderResult result = baseResult(codeResult, "geogebra", "html",
                sceneName);
        List<String> preflightIssues = GeoGebraCodeUtils.validateRenderBlockers(currentCode);
        if (!preflightIssues.isEmpty() && canRequestFix(attemptNumber, maxRenderRetries)) {
            retryState.setAttempts(attemptNumber);
            retryState.setRequestFix(true);
            retryState.setPendingFocusedError(ErrorSummarizer.buildRenderFixSummary(String.join("\n", preflightIssues)));
            retryState.setPendingStaticAuditIssues(preflightIssues);
            return failureResult(codeResult, "geogebra", "html", sceneName, currentCode, attemptNumber,
                    retryState.getPendingFocusedError(), null, retryState.getFixToolCalls(), start);
        }

        GeoGebraRenderService.RenderAttemptResult attempt = geoGebraRenderService.render(
                currentCode,
                sceneName,
                outputDir,
                context != null ? context::registerCancellationHook : null,
                context != null ? context::clearCancellationHook : null);
        result.setSuccess(attempt.isSuccess());
        result.setFinalGeneratedCode(currentCode);
        result.setArtifactPath(attempt.getPreviewPath());
        result.setGeometryPath(attempt.getGeometryPath());
        result.setAttempts(attemptNumber);
        result.setToolCalls(retryState.getFixToolCalls());
        if (attempt.isSuccess()) {
            retryState.reset();
        } else {
            retryState.setAttempts(attemptNumber);
            String error = StringUtils.hasText(attempt.getError()) ? attempt.getError() : "GeoGebra render failed";
            result.setLastError(error);
            if (canRequestFix(attemptNumber, maxRenderRetries)
                    && !ErrorSummarizer.isEnvironmentError(error)) {
                retryState.setRequestFix(true);
                retryState.setPendingFocusedError(error);
            }
        }
        result.setExecutionTimeSeconds(secondsSince(start));
        return result;
    }

    private boolean canRequestFix(int attemptNumber, int maxRenderRetries) {
        return attemptNumber < Math.max(maxRenderRetries, 0) + 1;
    }

    private RenderResult failureResult(CodeResult codeResult,
                                       String outputTarget,
                                       String artifactType,
                                       String sceneName,
                                       String generatedCode,
                                       int attempts,
                                       String error,
                                       String geometryPath,
                                       int toolCalls,
                                       Instant start) {
        RenderResult result = baseResult(codeResult, outputTarget, artifactType, sceneName);
        result.setSuccess(false);
        result.setFinalGeneratedCode(generatedCode);
        result.setAttempts(attempts);
        result.setLastError(error);
        result.setGeometryPath(geometryPath);
        result.setToolCalls(toolCalls);
        result.setExecutionTimeSeconds(secondsSince(start));
        return result;
    }

    private RenderResult baseResult(CodeResult codeResult, String outputTarget, String artifactType, String sceneName) {
        RenderResult result = new RenderResult();
        result.setSceneName(sceneName);
        result.setOutputTarget(outputTarget);
        result.setArtifactType(artifactType);
        result.setFinalGeneratedCode(codeResult.getGeneratedCode());
        return result;
    }

    private double secondsSince(Instant start) {
        return Duration.between(start, Instant.now()).toMillis() / 1000.0D;
    }

    public static final class RenderRetryState {
        private int attempts;
        private int fixToolCalls;
        private boolean requestFix;
        private String pendingFocusedError;
        private List<String> pendingStaticAuditIssues = new java.util.ArrayList<>();
        private final List<String> fixHistory = new java.util.ArrayList<>();

        private void prepareAttempt() {
            requestFix = false;
            pendingFocusedError = null;
            pendingStaticAuditIssues = new java.util.ArrayList<>();
        }

        public void recordFixResult(CodeFixResult result) {
            if (result == null) {
                return;
            }
            fixToolCalls += result.getToolCalls();
            String summary = summarizeFixAttempt(result);
            if (StringUtils.hasText(summary)) {
                fixHistory.add(summary);
            }
        }

        public void reset() {
            attempts = 0;
            fixToolCalls = 0;
            requestFix = false;
            pendingFocusedError = null;
            pendingStaticAuditIssues = new java.util.ArrayList<>();
            fixHistory.clear();
        }

        private String summarizeFixAttempt(CodeFixResult result) {
            String errorSignature = ErrorSummarizer.summarizeSignature(result.getErrorReason());
            String outcome;
            CodeFixResult.FixOutcome fixOutcome = result.getOutcome();
            if (fixOutcome != null) {
                switch (fixOutcome) {
                    case FIXED:
                        outcome = "code changed and passed static audit; render not yet confirmed";
                        break;
                    case APPLIED_WITH_ISSUES:
                        outcome = "applied but "
                                + result.getPostFixStaticAuditIssueCount() + " static issues remain";
                        break;
                    case UNCHANGED:
                        outcome = "code unchanged";
                        break;
                    case REJECTED_CONTRACT:
                        outcome = "candidate rejected by artifact contract: "
                                + result.getFailureReason();
                        break;
                    case INPUT_CORRUPTED:
                        outcome = "input text had encoding issues";
                        break;
                    case RATE_LIMIT_BLOCKED:
                        outcome = "provider rate limit exhausted";
                        break;
                    case FAILED:
                    default:
                        outcome = "fix request failed";
                        break;
                }
            } else if (result.isApplied()) {
                outcome = "applied code change";
            } else {
                outcome = StringUtils.hasText(result.getFailureReason())
                        ? result.getFailureReason()
                        : "no code change applied";
            }
            return StringUtils.hasText(errorSignature)
                    ? "Tried fixing " + errorSignature + " -> " + outcome
                    : outcome;
        }

        public int getAttempts() {
            return attempts;
        }

        private void setAttempts(int attempts) {
            this.attempts = attempts;
        }

        public int getFixToolCalls() {
            return fixToolCalls;
        }

        public boolean isRequestFix() {
            return requestFix;
        }

        private void setRequestFix(boolean requestFix) {
            this.requestFix = requestFix;
        }

        public String getPendingFocusedError() {
            return pendingFocusedError;
        }

        private void setPendingFocusedError(String pendingFocusedError) {
            this.pendingFocusedError = pendingFocusedError;
        }

        public List<String> getPendingStaticAuditIssues() {
            return pendingStaticAuditIssues;
        }

        private void setPendingStaticAuditIssues(List<String> pendingStaticAuditIssues) {
            this.pendingStaticAuditIssues = pendingStaticAuditIssues != null
                    ? new java.util.ArrayList<>(pendingStaticAuditIssues)
                    : new java.util.ArrayList<>();
        }

        public List<String> getFixHistory() {
            return new java.util.ArrayList<>(fixHistory);
        }
    }

    public static final class Result {
        private final RenderResult renderResult;
        private final int apiCalls;

        private Result(RenderResult renderResult, int apiCalls) {
            this.renderResult = renderResult;
            this.apiCalls = apiCalls;
        }

        public RenderResult getRenderResult() {
            return renderResult;
        }

        public int getApiCalls() {
            return apiCalls;
        }
    }
}
