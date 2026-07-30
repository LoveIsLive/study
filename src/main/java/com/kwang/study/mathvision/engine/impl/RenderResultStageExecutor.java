package com.kwang.study.mathvision.engine.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionResult;
import com.kwang.study.mathvision.engine.MathVisionStageExecutor;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.MathVisionArtifact;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.pojo.MathVisionVersion;
import com.kwang.study.mathvision.service.MathVisionFinalCodeArtifactService;
import com.kwang.study.mathvision.service.MathVisionFinalArtifactStorageService;
import com.kwang.study.mathvision.workflow.model.AiContentPart;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.model.CodeFixRequest;
import com.kwang.study.mathvision.workflow.model.CodeFixResult;
import com.kwang.study.mathvision.workflow.model.CodeFixSource;
import com.kwang.study.mathvision.workflow.model.CodeResult;
import com.kwang.study.mathvision.workflow.model.Narrative;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.RenderResult;
import com.kwang.study.mathvision.workflow.model.SceneEvaluationResult;
import com.kwang.study.mathvision.workflow.node.CodeFixNode;
import com.kwang.study.mathvision.workflow.node.RenderNode;
import com.kwang.study.mathvision.workflow.node.SceneEvaluationNode;
import com.kwang.study.mathvision.workflow.prompt.RenderFixPrompts;
import com.kwang.study.mathvision.workflow.prompt.SceneEvaluationPrompts;
import com.kwang.study.mathvision.workflow.prompt.StoryboardJsonBuilder;
import com.kwang.study.mathvision.workflow.util.ErrorSummarizer;
import com.kwang.study.mathvision.workflow.util.ProblemBundleContextBuilder;
import com.kwang.study.mathvision.workflow.util.TextHealthDiagnostics;
import com.kwang.study.mathvision.workflow.util.TokenEstimator;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@Component
public class RenderResultStageExecutor implements MathVisionStageExecutor {

    private static final Logger log = LoggerFactory.getLogger(RenderResultStageExecutor.class);

    // Match math-vision: render-fix and scene-evaluation-fix use SEPARATE budgets.
    private static final int DEFAULT_MAX_SCENE_EVALUATION_FIX_ATTEMPTS = 5;

    private final MathVisionArtifactMapper artifactMapper;
    private final MathVisionVersionMapper versionMapper;
    private final ObjectMapper objectMapper;
    private final RenderNode renderNode;
    private final SceneEvaluationNode sceneEvaluationNode;
    private final CodeFixNode codeFixNode;
    private final MathVisionFinalCodeArtifactService finalCodeArtifactService;
    private final MathVisionFinalArtifactStorageService finalArtifactStorageService;
    private final MathVisionModelCatalog modelCatalog;
    private final String outputRoot;

    public RenderResultStageExecutor(MathVisionArtifactMapper artifactMapper,
                                     MathVisionVersionMapper versionMapper,
                                     ObjectMapper objectMapper,
                                     RenderNode renderNode,
                                     SceneEvaluationNode sceneEvaluationNode,
                                     CodeFixNode codeFixNode,
                                     MathVisionFinalCodeArtifactService finalCodeArtifactService,
                                     MathVisionFinalArtifactStorageService finalArtifactStorageService,
                                     MathVisionModelCatalog modelCatalog,
                                     @Value("${mathvision.render.output-root:mathvision-runs}") String outputRoot) {
        this.artifactMapper = artifactMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
        this.renderNode = renderNode;
        this.sceneEvaluationNode = sceneEvaluationNode;
        this.codeFixNode = codeFixNode;
        this.finalCodeArtifactService = finalCodeArtifactService;
        this.finalArtifactStorageService = finalArtifactStorageService;
        this.modelCatalog = modelCatalog;
        this.outputRoot = outputRoot;
    }

    @Override
    public StageEnum stage() {
        return StageEnum.RENDER_RESULT;
    }

    @Override
    public MathVisionStageExecutionResult execute(MathVisionStageExecutionContext context) {
        MathVisionTask task = context.getTask();
        ProblemBundle bundle = load(task, StageEnum.PROBLEM_NORMALIZATION, ProblemBundle.class);
        Narrative narrative = load(task, StageEnum.VISUAL_STORYBOARD, Narrative.class);
        CodeResult codeResult = load(task, StageEnum.CODE_GENERATION, CodeResult.class);
        context.checkCanceled();

        RenderResult renderResult = null;
        RenderResult successfulRenderResult = null;
        SceneEvaluationResult sceneEvaluationResult = null;
        List<CodeFixResult> fixTrace = new ArrayList<>();
        List<AiMessage> renderFixConversation = new ArrayList<>();
        List<String> sceneFixHistory = new ArrayList<>();
        RenderNode.RenderRetryState renderRetryState = new RenderNode.RenderRetryState();
        int apiCalls = 0;
        int sceneFixAttempts = 0;
        String renderQuality = renderQuality();
        int renderMaxRetries = renderMaxRetries();
        int sceneEvaluationMaxRetries = sceneEvaluationMaxRetries();
        // Overall safety guard so alternating render/scene fixes cannot loop unbounded
        // (equivalent to PocketFlow's per-node self-limiting in math-vision).
        int maxTotalIterations = renderMaxRetries + sceneEvaluationMaxRetries + 2;

        log.info("MathVision render stage started, taskId={}, version={}, outputTarget={}, renderQuality={}, "
                        + "initialCodeLines={}, maxIterations={}",
                task.getId(),
                task.getCurrentVersion(),
                outputTarget(codeResult),
                renderQuality,
                countNonBlankLines(codeResult.getGeneratedCode()),
                maxTotalIterations);

        for (int iteration = 0; iteration < maxTotalIterations; iteration++) {
            log.info("MathVision render stage iteration started, taskId={}, iteration={}, renderAttempts={}, "
                            + "renderFixToolCalls={}, sceneFixAttempts={}, codeLines={}",
                    task.getId(),
                    iteration + 1,
                    renderRetryState.getAttempts(),
                    renderRetryState.getFixToolCalls(),
                    sceneFixAttempts,
                    countNonBlankLines(codeResult.getGeneratedCode()));
            RenderNode.Result renderNodeResult = renderNode.run(
                    task, codeResult, narrative, renderRetryState, renderQuality,
                    renderMaxRetries, outputDir(task), context);
            renderResult = renderNodeResult.getRenderResult();
            apiCalls += renderNodeResult.getApiCalls();
            log.info("MathVision render attempt result, taskId={}, iteration={}, success={}, attempts={}, "
                            + "executionSeconds={}, artifactPath={}, geometryPath={}, requestFix={}, errorSignature={}",
                    task.getId(),
                    iteration + 1,
                    renderResult.isSuccess(),
                    renderResult.getAttempts(),
                    renderResult.getExecutionTimeSeconds(),
                    renderResult.getArtifactPath(),
                    renderResult.getGeometryPath(),
                    renderRetryState.isRequestFix(),
                    errorSignature(renderResult.getLastError()));

            if (!renderResult.isSuccess()) {
                // RenderNode already enforces the render-fix cap via canRequestFix().
                if (!renderRetryState.isRequestFix()) {
                    log.warn("MathVision render stage stops without CodeFix, taskId={}, iteration={}, attempts={}, "
                                    + "errorSignature={}",
                            task.getId(),
                            iteration + 1,
                            renderResult.getAttempts(),
                            errorSignature(renderResult.getLastError()));
                    break;
                }
                CodeFixRequest request = buildRenderFixRequest(
                        bundle, narrative, codeResult, renderResult, renderRetryState, fixTrace);
                log.info("MathVision routing render failure to CodeFix, taskId={}, iteration={}, attempts={}, "
                                + "staticIssues={}, fixHistory={}, promptTokens~{}, errorSignature={}",
                        task.getId(),
                        iteration + 1,
                        renderResult.getAttempts(),
                        request.getStaticAuditIssueCount(),
                        request.getFixHistory() != null ? request.getFixHistory().size() : 0,
                        estimateCodeFixPromptTokens(request),
                        errorSignature(request.getErrorReason()));
                CodeFixNode.Result fixNodeResult = codeFixNode.run(
                        task, request, context, renderFixConversation);
                CodeFixResult fixResult = fixNodeResult.getFixResult();
                fixTrace.add(fixResult);
                renderRetryState.recordFixResult(fixResult);
                appendFixConversation(
                        renderFixConversation,
                        fixResult != null ? fixResult.getCurrentRequestPrompt() : null,
                        fixNodeResult.getAssistantTranscript(),
                        renderFixConversationRounds());
                apiCalls += fixNodeResult.getApiCalls();
                log.info("MathVision render CodeFix result, taskId={}, iteration={}, outcome={}, applied={}, "
                                + "apiCalls={}, executionSeconds={}, postStaticIssues={}, failureReason={}",
                        task.getId(),
                        iteration + 1,
                        fixResult != null ? fixResult.getOutcome() : null,
                        fixResult != null && fixResult.isApplied(),
                        fixNodeResult.getApiCalls(),
                        fixResult != null ? fixResult.getExecutionTimeSeconds() : 0.0D,
                        fixResult != null ? fixResult.getPostFixStaticAuditIssueCount() : 0,
                        abbreviate(fixResult != null ? fixResult.getFailureReason() : null, 500));
                if (!applyFix(codeResult, fixResult)) {
                    log.warn("MathVision render CodeFix not applied, taskId={}, iteration={}, outcome={}, reason={}",
                            task.getId(),
                            iteration + 1,
                            fixResult != null ? fixResult.getOutcome() : null,
                            abbreviate(fixResult != null ? fixResult.getFailureReason() : null, 500));
                    break;
                }
                log.info("MathVision render CodeFix applied, taskId={}, iteration={}, newCodeLines={}",
                        task.getId(), iteration + 1, countNonBlankLines(codeResult.getGeneratedCode()));
                continue;
            }

            SceneEvaluationNode.Result sceneNodeResult = sceneEvaluationNode.run(
                    task, narrative, codeResult, renderResult, sceneFixAttempts, context);
            sceneEvaluationResult = sceneNodeResult.getSceneEvaluationResult();
            apiCalls += sceneNodeResult.getApiCalls();
            log.info("MathVision scene evaluation result, taskId={}, iteration={}, approved={}, evaluated={}, "
                            + "revisionAttempts={}, totalIssues={}, blockingIssues={}, apiCalls={}, executionSeconds={}, reason={}",
                    task.getId(),
                    iteration + 1,
                    sceneEvaluationResult != null && sceneEvaluationResult.isApproved(),
                    sceneEvaluationResult != null && sceneEvaluationResult.isEvaluated(),
                    sceneEvaluationResult != null ? sceneEvaluationResult.getRevisionAttempts() : 0,
                    sceneEvaluationResult != null ? sceneEvaluationResult.getTotalIssueCount() : 0,
                    sceneEvaluationResult != null ? sceneEvaluationResult.getBlockingIssueCount() : 0,
                    sceneNodeResult.getApiCalls(),
                    sceneEvaluationResult != null ? sceneEvaluationResult.getExecutionTimeSeconds() : 0.0D,
                    abbreviate(sceneEvaluationResult != null ? sceneEvaluationResult.getGateReason() : null, 500));

            boolean successfulArtifactRetained = true;
            try {
                retainSuccessfulArtifact(renderResult, outputDir(task));
            } catch (Exception e) {
                successfulArtifactRetained = false;
                log.warn("MathVision successful render retention failed, taskId={}, artifactPath={}, error={}",
                        task.getId(), renderResult.getArtifactPath(), e.getMessage(), e);
            }
            // Keep the latest successful artifact even if a later scene-layout fix breaks rendering.
            successfulRenderResult = renderResult;

            if (sceneEvaluationResult.isApproved()) {
                log.info("MathVision render stage scene evaluation approved, taskId={}, iteration={}",
                        task.getId(), iteration + 1);
                break;
            }
            if (!successfulArtifactRetained) {
                sceneEvaluationResult.setGateReason(
                        "Scene layout repair stopped because the successful artifact could not be retained safely");
                break;
            }
            if (sceneFixAttempts >= sceneEvaluationMaxRetries) {
                sceneEvaluationResult.setGateReason("Layout issues remain after "
                        + sceneFixAttempts + " fix attempts; final rendered artifact retained");
                log.warn("MathVision scene evaluation fix budget exhausted, taskId={}, iteration={}, attempts={}, "
                                + "maxAttempts={}",
                        task.getId(),
                        iteration + 1,
                        sceneFixAttempts,
                        sceneEvaluationMaxRetries);
                break;
            }
            sceneFixAttempts++;
            CodeFixRequest request = buildSceneEvaluationFixRequest(
                    narrative, codeResult, renderResult, sceneEvaluationResult, sceneFixHistory);
            if (StringUtils.hasText(request.getErrorReason())) {
                sceneFixHistory.add(request.getErrorReason());
            }
            log.info("MathVision routing scene evaluation to CodeFix, taskId={}, iteration={}, sceneFixAttempt={}, "
                            + "promptTokens~{}, issueSummary={}",
                    task.getId(),
                    iteration + 1,
                    sceneFixAttempts,
                    estimateCodeFixPromptTokens(request),
                    abbreviate(request.getErrorReason(), 500));
            CodeFixNode.Result fixNodeResult = codeFixNode.run(
                    task, request, context, List.of());
            CodeFixResult fixResult = fixNodeResult.getFixResult();
            fixTrace.add(fixResult);
            apiCalls += fixNodeResult.getApiCalls();
            log.info("MathVision scene evaluation CodeFix result, taskId={}, iteration={}, outcome={}, applied={}, "
                            + "apiCalls={}, executionSeconds={}, postStaticIssues={}, failureReason={}",
                    task.getId(),
                    iteration + 1,
                    fixResult != null ? fixResult.getOutcome() : null,
                    fixResult != null && fixResult.isApplied(),
                    fixNodeResult.getApiCalls(),
                    fixResult != null ? fixResult.getExecutionTimeSeconds() : 0.0D,
                    fixResult != null ? fixResult.getPostFixStaticAuditIssueCount() : 0,
                    abbreviate(fixResult != null ? fixResult.getFailureReason() : null, 500));
            if (!applyFix(codeResult, fixResult)) {
                sceneEvaluationResult.setGateReason(
                        "Scene layout repair stopped because CodeFix did not apply a change; "
                                + "final rendered artifact retained");
                log.warn("MathVision scene evaluation CodeFix not applied, taskId={}, iteration={}, outcome={}, reason={}",
                        task.getId(),
                        iteration + 1,
                        fixResult != null ? fixResult.getOutcome() : null,
                        abbreviate(fixResult != null ? fixResult.getFailureReason() : null, 500));
                break;
            }
            log.info("MathVision scene evaluation CodeFix applied, taskId={}, iteration={}, newCodeLines={}",
                    task.getId(), iteration + 1, countNonBlankLines(codeResult.getGeneratedCode()));
        }

        RenderResult lastRenderResult = renderResult;
        boolean renderFinalSuccess = lastRenderResult != null && lastRenderResult.isSuccess();
        boolean renderSuccess = successfulRenderResult != null;
        if (renderSuccess) {
            renderResult = successfulRenderResult;
        }
        boolean sceneApproved = sceneEvaluationResult == null || sceneEvaluationResult.isApproved();
        boolean sceneEvaluationWarning = renderSuccess && !sceneApproved;
        boolean stageSuccess = renderSuccess;
        String storageError = null;
        String finalCodeWritebackError = null;
        MathVisionFinalCodeArtifactService.WritebackResult finalCodeWriteback = null;

        if (renderSuccess) {
            try {
                finalCodeWriteback = finalCodeArtifactService.persistFinalCode(task, renderResult);
            } catch (Exception e) {
                // Match math-vision output semantics: metadata persistence must not erase render success.
                finalCodeWritebackError = trim(e.getMessage());
                log.warn("MathVision final render code writeback failed, taskId={}, error={}",
                        task.getId(), finalCodeWritebackError, e);
            }
            try {
                String localArtifactPath = renderResult.getArtifactPath();
                MathVisionFinalArtifactStorageService.StoredArtifact stored =
                        finalArtifactStorageService.store(task, renderResult);
                renderResult.setLocalArtifactPath(localArtifactPath);
                renderResult.setStorageArtifactPath(stored.getPath());
                renderResult.setArtifactPath(stored.getPath());
                renderResult.setArtifactType(stored.getArtifactType());
                renderResult.setArtifactFileName(stored.getFileName());
                renderResult.setArtifactMimeType(stored.getMimeType());
                if ("mp4".equalsIgnoreCase(renderResult.getArtifactType())) {
                    renderResult.setVideoPath(stored.getPath());
                }
                log.info("MathVision final artifact stored, taskId={}, localPath={}, storedPath={}, artifactType={}",
                        task.getId(), localArtifactPath, stored.getPath(), stored.getArtifactType());
            } catch (Exception e) {
                storageError = trim(e.getMessage());
                log.warn("MathVision final artifact storage failed; retained render remains successful, "
                                + "taskId={}, artifactPath={}, error={}",
                        task.getId(), renderResult.getArtifactPath(), storageError, e);
            }
        }

        ObjectNode resultJson = objectMapper.createObjectNode();
        resultJson.put("apiCalls", apiCalls);
        resultJson.put("renderQuality", renderQuality);
        resultJson.put("renderMaxRetries", renderMaxRetries);
        resultJson.put("sceneEvaluationMaxRetries", sceneEvaluationMaxRetries);
        resultJson.put("renderFixConversationMessages", renderFixConversation.size());
        resultJson.put("sceneFixConversationMessages", 0);
        resultJson.put("sceneFixHistoryEntries", sceneFixHistory.size());
        resultJson.set("renderResult", objectMapper.valueToTree(renderResult));
        resultJson.set("lastRenderResult", objectMapper.valueToTree(lastRenderResult));
        resultJson.set("sceneEvaluation", objectMapper.valueToTree(sceneEvaluationResult));
        resultJson.put("success", stageSuccess);
        resultJson.put("renderSuccess", renderSuccess);
        resultJson.put("renderEverSucceeded", renderSuccess);
        resultJson.put("renderFinalSuccess", renderFinalSuccess);
        resultJson.put("sceneEvaluationApproved", sceneApproved);
        resultJson.put("sceneEvaluationWarning", sceneEvaluationWarning);
        resultJson.put("finalCodeWritebackUpdated",
                finalCodeWriteback != null && finalCodeWriteback.isUpdated());
        if (finalCodeWriteback != null) {
            resultJson.put("finalCodePreviousStageVersion", finalCodeWriteback.getPreviousVersion());
            resultJson.put("finalCodeStageVersion", finalCodeWriteback.getFinalVersion());
        }
        if (StringUtils.hasText(finalCodeWritebackError)) {
            resultJson.put("finalCodeWritebackError", finalCodeWritebackError);
        }
        if (StringUtils.hasText(storageError)) {
            resultJson.put("artifactStorageWarning", storageError);
        }
        resultJson.put("artifactPath", renderResult != null ? renderResult.getArtifactPath() : null);
        resultJson.put("artifactType", renderResult != null ? renderResult.getArtifactType() : null);
        ArrayNode fixTraceNode = resultJson.putArray("codeFixTrace");
        for (CodeFixResult fixResult : fixTrace) {
            fixTraceNode.add(objectMapper.valueToTree(fixResult));
        }

        String errorType = !stageSuccess ? "render_error" : null;
        String errorMessage = !stageSuccess ? buildRenderFailureMessage(renderResult, fixTrace) : null;

        log.info("MathVision render stage completed, taskId={}, success={}, renderSuccess={}, renderFinalSuccess={}, "
                        + "sceneApproved={}, "
                        + "iterations={}, fixEvents={}, apiCalls={}, finalArtifactPath={}, errorType={}, errorSignature={}",
                task.getId(),
                stageSuccess,
                renderSuccess,
                renderFinalSuccess,
                sceneApproved,
                renderResult != null ? renderResult.getAttempts() : 0,
                fixTrace.size(),
                apiCalls,
                stageSuccess && renderResult != null ? renderResult.getArtifactPath() : null,
                stageSuccess ? null : errorType,
                stageSuccess ? null : errorSignature(errorMessage));

        return MathVisionStageExecutionResult.builder()
                .artifactJson(toPrettyJson(renderResult))
                .resultJson(toPrettyJson(resultJson))
                .changeSource("initial_generation")
                .changeSummary(stageSuccess
                        ? StringUtils.hasText(storageError)
                        ? "rendered final artifact; platform storage archive reported a warning"
                        : sceneEvaluationWarning
                        ? "rendered final artifact with scene layout warning"
                        : "rendered and evaluated final artifact"
                        : "render or final artifact storage failed")
                .finalArtifactPath(stageSuccess && renderResult != null ? renderResult.getArtifactPath() : null)
                .finalArtifactType(stageSuccess && renderResult != null ? renderResult.getArtifactType() : null)
                .failed(!stageSuccess)
                .errorType(stageSuccess ? null : errorType)
                .errorMessage(stageSuccess ? null : errorMessage)
                .build();
    }

    private boolean applyFix(CodeResult codeResult, CodeFixResult fixResult) {
        if (fixResult == null || !fixResult.isApplied()
                || !StringUtils.hasText(fixResult.getFixedGeneratedCode())) {
            return false;
        }
        codeResult.setGeneratedCode(fixResult.getFixedGeneratedCode());
        return true;
    }

    private void appendFixConversation(List<AiMessage> conversation,
                                       String userPrompt,
                                       String assistantTranscript,
                                       int maxRounds) {
        if (!StringUtils.hasText(userPrompt) || !StringUtils.hasText(assistantTranscript)) {
            return;
        }
        conversation.add(AiMessage.user(List.of(AiContentPart.text(userPrompt))));
        conversation.add(new AiMessage(
                "assistant", List.of(AiContentPart.text(assistantTranscript))));
        while (conversation.size() > Math.max(maxRounds, 1) * 2) {
            conversation.remove(0);
            conversation.remove(0);
        }
    }

    private int renderFixConversationRounds() {
        if (modelCatalog == null || modelCatalog.getWorkflow() == null
                || modelCatalog.getWorkflow().getRenderFixConversationRounds() == null) {
            return 10;
        }
        return Math.max(modelCatalog.getWorkflow().getRenderFixConversationRounds(), 1);
    }

    private int renderMaxRetries() {
        if (modelCatalog == null || modelCatalog.getWorkflow() == null
                || modelCatalog.getWorkflow().getRenderMaxRetries() == null) {
            return RenderNode.DEFAULT_MAX_RENDER_RETRIES;
        }
        return Math.max(modelCatalog.getWorkflow().getRenderMaxRetries(), 0);
    }

    private int sceneEvaluationMaxRetries() {
        if (modelCatalog == null || modelCatalog.getWorkflow() == null
                || modelCatalog.getWorkflow().getSceneEvaluationMaxRetries() == null) {
            return DEFAULT_MAX_SCENE_EVALUATION_FIX_ATTEMPTS;
        }
        return Math.max(modelCatalog.getWorkflow().getSceneEvaluationMaxRetries(), 0);
    }

    private String renderQuality() {
        if (modelCatalog == null || modelCatalog.getWorkflow() == null
                || !StringUtils.hasText(modelCatalog.getWorkflow().getRenderQuality())) {
            return "low";
        }
        String configured = modelCatalog.getWorkflow().getRenderQuality().trim().toLowerCase();
        return "high".equals(configured) || "medium".equals(configured) ? configured : "low";
    }

    private void retainSuccessfulArtifact(RenderResult renderResult, Path renderOutputDir) throws IOException {
        if (renderResult == null || !renderResult.isSuccess()
                || !StringUtils.hasText(renderResult.getArtifactPath())) {
            throw new IOException("Successful render artifact path is missing");
        }
        Path source = Paths.get(renderResult.getArtifactPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("Successful render artifact does not exist: " + source);
        }

        Path retainedDir = renderOutputDir.resolve("retained").toAbsolutePath().normalize();
        Files.createDirectories(retainedDir);
        String extension = fileExtension(source);
        Path retainedArtifact = retainedDir.resolve("final" + extension).normalize();
        moveReplacing(source, retainedArtifact);
        renderResult.setArtifactPath(retainedArtifact.toString());
        if ("mp4".equalsIgnoreCase(renderResult.getArtifactType())) {
            renderResult.setVideoPath(retainedArtifact.toString());
        }

        if (StringUtils.hasText(renderResult.getGeometryPath())) {
            Path geometrySource = Paths.get(renderResult.getGeometryPath()).toAbsolutePath().normalize();
            if (Files.isRegularFile(geometrySource)) {
                Path retainedGeometry = retainedDir.resolve("final_geometry.json").normalize();
                moveReplacing(geometrySource, retainedGeometry);
                renderResult.setGeometryPath(retainedGeometry.toString());
            }
        }
        log.info("MathVision successful render retained, artifactPath={}, geometryPath={}",
                renderResult.getArtifactPath(), renderResult.getGeometryPath());
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        if (source.equals(target)) {
            return;
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveError) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String fileExtension(Path path) {
        String fileName = path.getFileName() != null ? path.getFileName().toString() : "";
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : "";
    }

    private CodeFixRequest buildRenderFixRequest(ProblemBundle bundle,
                                                 Narrative narrative,
                                                 CodeResult codeResult,
                                                 RenderResult renderResult,
                                                 RenderNode.RenderRetryState renderRetryState,
                                                 List<CodeFixResult> fixTrace) {
        String outputTarget = outputTarget(codeResult);
        CodeFixRequest request = baseFixRequest(bundle, narrative, codeResult, fixTrace, outputTarget);
        request.setSource(CodeFixSource.CODE_RENDER);
        List<String> staticIssues = renderRetryState.getPendingStaticAuditIssues();
        if (staticIssues == null) {
            staticIssues = new ArrayList<>();
        }
        String focusedError = renderRetryState.getPendingFocusedError();
        String renderError = renderResult != null ? renderResult.getLastError() : "Render failed";
        request.setErrorReason(StringUtils.hasText(focusedError)
                ? focusedError
                : !staticIssues.isEmpty()
                ? ErrorSummarizer.buildRenderFixSummary(String.join("\n", staticIssues))
                : renderError);
        request.setRenderError(renderError);
        request.setErrorContextMode("summary_signature");
        request.setStaticAuditIssueCount(staticIssues.size());
        request.setStaticAuditSummary(String.join(" | ", staticIssues));
        request.setFixHistory(renderRetryState.getFixHistory());
        String storyboardJson = narrative != null && narrative.hasStoryboard()
                ? StoryboardJsonBuilder.buildForCodegen(narrative.getStoryboard(), outputTarget)
                : StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON;
        request.setInputTextHealth(TextHealthDiagnostics.summarize(
                request.getErrorReason() + "\n" + storyboardJson));
        request.setRulesPrompt(RenderFixPrompts.buildRulesPrompt(outputTarget));
        request.setFixedContextPrompt(RenderFixPrompts.buildFixedContextPrompt(
                bundle, request.getTargetDescription(), outputTarget));
        request.setStoryboardJson(StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON);
        return request;
    }

    private CodeFixRequest buildSceneEvaluationFixRequest(Narrative narrative,
                                                          CodeResult codeResult,
                                                          RenderResult renderResult,
                                                          SceneEvaluationResult sceneEvaluationResult,
                                                          List<String> sceneFixHistory) {
        String outputTarget = outputTarget(codeResult);
        String sceneName = codeResult.getSceneName();
        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.SCENE_LAYOUT_EVALUATION);
        request.setGeneratedCode(codeResult.getGeneratedCode());
        request.setSceneName(sceneName);
        request.setExpectedSceneName("geogebra".equalsIgnoreCase(outputTarget)
                ? com.kwang.study.mathvision.workflow.util.GeoGebraCodeUtils.EXPECTED_FIGURE_NAME
                : "MainScene");
        request.setOutputTarget(outputTarget);
        request.setProblemBundle(null);
        request.setTargetDescription(null);
        request.setFixHistory(sceneFixHistory != null
                ? new ArrayList<>(sceneFixHistory)
                : new ArrayList<>());
        String issueSummary = sceneEvaluationResult != null
                ? sceneEvaluationNode.buildIssueSummaryForFix(sceneEvaluationResult)
                : "Scene evaluation requested a layout fix";
        String sceneEvaluationJson = sceneEvaluationResult != null
                ? sceneEvaluationNode.buildFixReportJsonForFix(sceneEvaluationResult, narrative)
                : "{}";
        request.setErrorReason(issueSummary);
        request.setSceneEvaluationJson(sceneEvaluationJson);
        request.setRenderError(renderResult != null ? renderResult.getLastError() : null);
        String storyboardJson = narrative != null && narrative.hasStoryboard()
                ? StoryboardJsonBuilder.buildForSceneEvaluationFix(narrative.getStoryboard(), outputTarget)
                : StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON;
        request.setStoryboardJson(storyboardJson);
        request.setInputTextHealth(TextHealthDiagnostics.summarize(issueSummary + "\n" + storyboardJson));
        request.setRulesPrompt(SceneEvaluationPrompts.buildLayoutFixRulesPrompt(outputTarget));
        request.setFixedContextPrompt(SceneEvaluationPrompts.buildLayoutFixFixedContextPrompt(outputTarget));
        return request;
    }

    private CodeFixRequest baseFixRequest(ProblemBundle bundle,
                                          Narrative narrative,
                                          CodeResult codeResult,
                                          List<CodeFixResult> fixTrace,
                                          String outputTarget) {
        String sceneName = codeResult.getSceneName();
        CodeFixRequest request = new CodeFixRequest();
        request.setGeneratedCode(codeResult.getGeneratedCode());
        request.setProblemBundle(bundle);
        request.setTargetDescription(ProblemBundleContextBuilder.workflowTargetDescription(
                bundle, sceneName, codeResult.getDescription(), outputTarget));
        request.setSceneName(sceneName);
        request.setExpectedSceneName(sceneName);
        request.setOutputTarget(outputTarget);
        request.setFixHistory(buildFixHistory(fixTrace));
        return request;
    }

    private List<String> buildFixHistory(List<CodeFixResult> fixTrace) {
        List<String> history = new ArrayList<>();
        if (fixTrace == null) {
            return history;
        }
        for (int i = 0; i < fixTrace.size(); i++) {
            CodeFixResult fix = fixTrace.get(i);
            history.add("attempt " + (i + 1)
                    + ": outcome=" + (fix == null ? "null" : fix.getOutcome())
                    + ", applied=" + (fix != null && fix.isApplied())
                    + ", reason=" + (fix == null ? "" : fix.getFailureReason()));
        }
        return history;
    }

    private int estimateCodeFixPromptTokens(CodeFixRequest request) {
        if (request == null) {
            return 0;
        }
        return TokenEstimator.estimateTokens(request.getRulesPrompt())
                + TokenEstimator.estimateTokens(request.getFixedContextPrompt())
                + TokenEstimator.estimateTokens(request.getErrorReason())
                + TokenEstimator.estimateTokens(request.getStaticAuditSummary())
                + TokenEstimator.estimateTokens(request.getSceneEvaluationJson())
                + TokenEstimator.estimateTokens(request.getGeneratedCode());
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

    private String errorSignature(String error) {
        String signature = ErrorSummarizer.summarizeSignature(error);
        if (!StringUtils.hasText(signature)) {
            signature = abbreviate(error, 240);
        }
        return abbreviate(signature, 240);
    }

    private String buildRenderFailureMessage(RenderResult renderResult, List<CodeFixResult> fixTrace) {
        String renderError = renderResult != null ? trim(renderResult.getLastError()) : "Render failed";
        String codeFixFailure = latestUnappliedCodeFixFailure(fixTrace);
        if (!StringUtils.hasText(codeFixFailure)) {
            return renderError;
        }
        return trim("[codefix]\n" + codeFixFailure + "\n\n[render]\n" + renderError);
    }

    private String latestUnappliedCodeFixFailure(List<CodeFixResult> fixTrace) {
        if (fixTrace == null || fixTrace.isEmpty()) {
            return "";
        }
        for (int i = fixTrace.size() - 1; i >= 0; i--) {
            CodeFixResult fix = fixTrace.get(i);
            if (fix == null) {
                return "Latest CodeFix attempt returned no result.";
            }
            if (fix.isApplied()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Latest CodeFix attempt did not apply a fix");
            if (fix.getOutcome() != null) {
                sb.append(": outcome=").append(fix.getOutcome());
            }
            if (StringUtils.hasText(fix.getFailureReason())) {
                sb.append(", reason=").append(abbreviate(fix.getFailureReason(), 500));
            }
            return sb.toString();
        }
        return "";
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String outputTarget(CodeResult codeResult) {
        return codeResult != null && StringUtils.hasText(codeResult.getOutputTarget())
                ? codeResult.getOutputTarget() : "manim";
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;
    }

    private Path outputDir(MathVisionTask task) {
        return Paths.get(outputRoot, "task-" + task.getId(),
                "v" + task.getCurrentVersion(), "render").toAbsolutePath().normalize();
    }

    private <T> T load(MathVisionTask task, StageEnum stage, Class<T> type) {
        MathVisionVersion version = currentVersion(task);
        Integer stageVersion;
        if (stage == StageEnum.PROBLEM_NORMALIZATION) {
            stageVersion = version.getPnVersion();
        } else if (stage == StageEnum.VISUAL_STORYBOARD) {
            stageVersion = version.getVsVersion();
        } else {
            stageVersion = version.getCgVersion();
        }
        if (stageVersion == null) {
            throw new IllegalStateException("Missing upstream artifact: " + stage.getCode());
        }
        MathVisionArtifact artifact = artifactMapper.findByTaskStageVersion(
                task.getId(), stage.getCode(), stageVersion);
        if (artifact == null || !StringUtils.hasText(artifact.getArtifactJson())) {
            throw new IllegalStateException("Empty upstream artifact: " + stage.getCode());
        }
        try {
            return objectMapper.readValue(artifact.getArtifactJson(), type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse " + stage.getCode() + " artifact: " + e.getMessage(), e);
        }
    }

    private MathVisionVersion currentVersion(MathVisionTask task) {
        MathVisionVersion version = versionMapper.findCurrent(task.getId());
        if (version == null && task.getCurrentVersion() != null) {
            version = versionMapper.findByTaskVersion(task.getId(), task.getCurrentVersion());
        }
        if (version == null) {
            throw new IllegalStateException("Missing current MathVision version");
        }
        return version;
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize render stage result: " + e.getMessage(), e);
        }
    }
}
