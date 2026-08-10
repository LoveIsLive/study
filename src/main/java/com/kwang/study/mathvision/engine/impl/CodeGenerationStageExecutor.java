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
import com.kwang.study.mathvision.workflow.model.CodeEvaluationResult;
import com.kwang.study.mathvision.workflow.model.CodeFixRequest;
import com.kwang.study.mathvision.workflow.model.CodeFixResult;
import com.kwang.study.mathvision.workflow.model.CodeFixSource;
import com.kwang.study.mathvision.workflow.model.CodeResult;
import com.kwang.study.mathvision.workflow.model.AiContentPart;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.model.Narrative;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.StageGenerationMode;
import com.kwang.study.mathvision.workflow.model.StageGenerationRequest;
import com.kwang.study.mathvision.workflow.node.CodeEvaluationNode;
import com.kwang.study.mathvision.workflow.node.CodeFixNode;
import com.kwang.study.mathvision.workflow.node.CodeGenerationNode;
import com.kwang.study.mathvision.workflow.prompt.CodeEvaluationPrompts;
import com.kwang.study.mathvision.workflow.prompt.StoryboardJsonBuilder;
import com.kwang.study.mathvision.workflow.util.NodeExecutionLogger;
import com.kwang.study.mathvision.workflow.util.ProblemBundleContextBuilder;
import com.kwang.study.mathvision.workflow.util.TextHealthDiagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
public class CodeGenerationStageExecutor implements MathVisionStageExecutor {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationStageExecutor.class);
    private static final int DEFAULT_MAX_CODE_EVALUATION_FIX_ATTEMPTS = 3;

    private final MathVisionArtifactMapper artifactMapper;
    private final MathVisionVersionMapper versionMapper;
    private final ObjectMapper objectMapper;
    private final CodeGenerationNode codeGenerationNode;
    private final CodeEvaluationNode codeEvaluationNode;
    private final CodeFixNode codeFixNode;
    private final MathVisionModelCatalog modelCatalog;

    public CodeGenerationStageExecutor(MathVisionArtifactMapper artifactMapper,
                                       MathVisionVersionMapper versionMapper,
                                       ObjectMapper objectMapper,
                                       CodeGenerationNode codeGenerationNode,
                                       CodeEvaluationNode codeEvaluationNode,
                                       CodeFixNode codeFixNode,
                                       MathVisionModelCatalog modelCatalog) {
        this.artifactMapper = artifactMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
        this.codeGenerationNode = codeGenerationNode;
        this.codeEvaluationNode = codeEvaluationNode;
        this.codeFixNode = codeFixNode;
        this.modelCatalog = modelCatalog;
    }

    @Override
    public StageEnum stage() {
        return StageEnum.CODE_GENERATION;
    }

    @Override
    public MathVisionStageExecutionResult execute(MathVisionStageExecutionContext context) {
        MathVisionTask task = context.getTask();
        ProblemBundle bundle = load(task, StageEnum.PROBLEM_NORMALIZATION, ProblemBundle.class);
        Narrative narrative = load(task, StageEnum.VISUAL_STORYBOARD, Narrative.class);
        StageGenerationRequest<CodeResult> generationRequest = context.isUserRevision()
                ? StageGenerationRequest.<CodeResult>builder()
                        .mode(StageGenerationMode.USER_REVISION)
                        .existingArtifact(readExistingCode(context))
                        .instruction(context.getInstruction())
                        .baseStageVersion(context.getBaseStageVersion())
                        .build()
                : StageGenerationRequest.initialGeneration();
        GenerationRun generationRun = runCodeGenerationWithRetry(
                task, bundle, narrative, generationRequest, context);
        CodeGenerationNode.Result nodeResult = generationRun.result;
        CodeResult codeResult = nodeResult.getCodeResult();
        int apiCalls = nodeResult.getApiCalls();

        List<CodeEvaluationResult> evaluationAttempts = new ArrayList<>();
        List<CodeFixResult> fixTrace = new ArrayList<>();
        List<AiMessage> codeFixConversation = new ArrayList<>();
        CodeEvaluationResult latestEvaluation = null;
        boolean revisedCodeApplied = false;
        int maxCodeEvaluationFixAttempts = codeEvaluationMaxRetries();
        for (int fixAttempt = 0; fixAttempt <= maxCodeEvaluationFixAttempts; fixAttempt++) {
            context.checkCanceled();
            int evaluationAttempt = fixAttempt + 1;
            int currentFixAttempt = fixAttempt;
            boolean currentRevisedCodeApplied = revisedCodeApplied;
            CodeEvaluationNode.Result evaluationNodeResult = NodeExecutionLogger.execute(
                    task.getId(),
                    stage().getCode(),
                    "CodeEvaluationNode",
                    "attempt=" + evaluationAttempt,
                    () -> codeEvaluationNode.run(
                            task,
                            bundle,
                            narrative,
                            codeResult,
                            currentFixAttempt,
                            currentRevisedCodeApplied,
                            context),
                    CodeEvaluationNode.Result::getApiCalls);
            latestEvaluation = evaluationNodeResult.getEvaluationResult();
            evaluationAttempts.add(latestEvaluation);
            apiCalls += evaluationNodeResult.getApiCalls();
            if (latestEvaluation.isApprovedForRender()) {
                break;
            }
            if (fixAttempt >= maxCodeEvaluationFixAttempts) {
                break;
            }
            CodeFixRequest fixRequest = buildEvaluationFixRequest(
                    bundle, narrative, codeResult, latestEvaluation, fixTrace);
            CodeFixNode.Result fixNodeResult = NodeExecutionLogger.execute(
                    task.getId(),
                    stage().getCode(),
                    "CodeFixNode",
                    "source=code_evaluation,attempt=" + evaluationAttempt,
                    () -> codeFixNode.run(task, fixRequest, context, codeFixConversation),
                    CodeFixNode.Result::getApiCalls);
            CodeFixResult fixResult = fixNodeResult.getFixResult();
            fixTrace.add(fixResult);
            appendFixConversation(
                    codeFixConversation,
                    fixResult != null ? fixResult.getCurrentRequestPrompt() : null,
                    fixNodeResult.getAssistantTranscript(),
                    codeFixConversationRounds());
            apiCalls += fixNodeResult.getApiCalls();
            if (fixResult == null || !fixResult.isApplied()
                    || !StringUtils.hasText(fixResult.getFixedGeneratedCode())) {
                break;
            }
            codeResult.setGeneratedCode(fixResult.getFixedGeneratedCode());
            revisedCodeApplied = true;
        }
        codeResult.setToolCalls(apiCalls);

        boolean approved = latestEvaluation != null && latestEvaluation.isApprovedForRender();
        ObjectNode resultJson = objectMapper.createObjectNode();
        resultJson.put("apiCalls", apiCalls);
        resultJson.put("codeGenerationApiCalls", nodeResult.getApiCalls());
        resultJson.put("codeGenerationAttempts", generationRun.attempts);
        resultJson.put("codeEvaluationMaxRetries", maxCodeEvaluationFixAttempts);
        resultJson.put("codeFixConversationRounds", codeFixConversationRounds());
        ArrayNode generationFailures = resultJson.putArray("codeGenerationRetryFailures");
        for (String failure : generationRun.failures) {
            generationFailures.add(failure);
        }
        resultJson.put("lineCount", codeResult != null ? codeResult.codeLineCount() : 0);
        resultJson.put("sceneName", codeResult != null ? codeResult.getSceneName() : null);
        resultJson.put("artifactName", codeResult != null ? codeResult.getArtifactName() : null);
        resultJson.put("artifactFormat", codeResult != null ? codeResult.getArtifactFormat() : null);
        resultJson.put("outputTarget", codeResult != null ? codeResult.getOutputTarget() : null);
        resultJson.put("codeEvaluationApproved", approved);
        resultJson.put("codeEvaluationWarning", !approved);
        resultJson.put("codeEvaluationGateReason", latestEvaluation != null ? latestEvaluation.getGateReason() : "");
        resultJson.set("codeEvaluation", objectMapper.valueToTree(latestEvaluation));
        ArrayNode attemptsNode = resultJson.putArray("codeEvaluationAttempts");
        for (CodeEvaluationResult attempt : evaluationAttempts) {
            attemptsNode.add(objectMapper.valueToTree(attempt));
        }
        ArrayNode fixTraceNode = resultJson.putArray("codeFixTrace");
        for (CodeFixResult fixResult : fixTrace) {
            fixTraceNode.add(objectMapper.valueToTree(fixResult));
        }

        return MathVisionStageExecutionResult.builder()
                .artifactJson(toPrettyJson(codeResult))
                .resultJson(toPrettyJson(resultJson))
                .changeSource(context.isUserRevision() ? "user_revision" : "initial_generation")
                .changeSummary(context.isUserRevision()
                        ? "regenerate complete backend code and run code evaluation from user feedback"
                        : approved
                        ? "generate backend code and run code evaluation"
                        : "generate backend code; code evaluation still recommends revisions before render")
                // Match math-vision: exhausting code-review fixes does not block the Render node.
                .failed(false)
                .build();
    }

    private GenerationRun runCodeGenerationWithRetry(MathVisionTask task,
                                                       ProblemBundle bundle,
                                                       Narrative narrative,
                                                       StageGenerationRequest<CodeResult> request,
                                                       MathVisionStageExecutionContext context) {
        int maxRetries = 2;
        long retryDelayMillis = 2_000L;
        if (modelCatalog != null && modelCatalog.getWorkflow() != null) {
            Integer configuredRetries = modelCatalog.getWorkflow().getCodeGenerationMaxRetries();
            Long configuredDelay = modelCatalog.getWorkflow().getCodeGenerationRetryDelayMillis();
            if (configuredRetries != null) {
                maxRetries = Math.max(configuredRetries, 0);
            }
            if (configuredDelay != null) {
                retryDelayMillis = Math.max(configuredDelay, 0L);
            }
        }

        List<String> failures = new ArrayList<>();
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            context.checkCanceled();
            try {
                int generationAttempt = attempt + 1;
                CodeGenerationNode.Result result = NodeExecutionLogger.execute(
                        task.getId(),
                        stage().getCode(),
                        "CodeGenerationNode",
                        "attempt=" + generationAttempt,
                        () -> codeGenerationNode.run(task, bundle, narrative, request, context),
                        CodeGenerationNode.Result::getApiCalls);
                return new GenerationRun(result, attempt + 1, failures);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (RuntimeException e) {
                String failure = "attempt " + (attempt + 1) + ": " + abbreviate(e.getMessage(), 1_000);
                failures.add(failure);
                if (attempt >= maxRetries) {
                    throw new IllegalStateException(
                            "Code generation failed after " + (attempt + 1) + " attempts: "
                                    + String.join("; ", failures), e);
                }
                log.warn("MathVision CodeGeneration retry scheduled, taskId={}, attempt={}, maxRetries={}, delayMillis={}, reason={}",
                        task.getId(), attempt + 1, maxRetries, retryDelayMillis, abbreviate(e.getMessage(), 500));
                sleepBeforeRetry(retryDelayMillis, context);
            }
        }
        throw new IllegalStateException("Code generation retry loop ended unexpectedly");
    }

    private int codeEvaluationMaxRetries() {
        if (modelCatalog == null || modelCatalog.getWorkflow() == null
                || modelCatalog.getWorkflow().getCodeEvaluationMaxRetries() == null) {
            return DEFAULT_MAX_CODE_EVALUATION_FIX_ATTEMPTS;
        }
        return Math.max(modelCatalog.getWorkflow().getCodeEvaluationMaxRetries(), 0);
    }

    private int codeFixConversationRounds() {
        if (modelCatalog == null || modelCatalog.getWorkflow() == null
                || modelCatalog.getWorkflow().getCodeFixConversationRounds() == null) {
            return 4;
        }
        return Math.max(modelCatalog.getWorkflow().getCodeFixConversationRounds(), 1);
    }

    private void sleepBeforeRetry(long delayMillis, MathVisionStageExecutionContext context) {
        if (delayMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
            context.checkCanceled();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Code generation retry wait was interrupted", e);
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "unknown error";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private static final class GenerationRun {
        private final CodeGenerationNode.Result result;
        private final int attempts;
        private final List<String> failures;

        private GenerationRun(CodeGenerationNode.Result result, int attempts, List<String> failures) {
            this.result = result;
            this.attempts = attempts;
            this.failures = new ArrayList<>(failures);
        }
    }

    private CodeResult readExistingCode(MathVisionStageExecutionContext context) {
        try {
            return objectMapper.readValue(context.getExistingArtifactJson(), CodeResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse user-revision generated code: " + e.getMessage(), e);
        }
    }

    private CodeFixRequest buildEvaluationFixRequest(ProblemBundle bundle,
                                                     Narrative narrative,
                                                     CodeResult codeResult,
                                                     CodeEvaluationResult evaluation,
                                                     List<CodeFixResult> fixTrace) {
        String outputTarget = StringUtils.hasText(codeResult.getOutputTarget()) ? codeResult.getOutputTarget() : "manim";
        String sceneName = StringUtils.hasText(evaluation.getSceneName())
                ? evaluation.getSceneName()
                : codeResult.getSceneName();
        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.CODE_EVALUATION);
        request.setGeneratedCode(codeResult.getGeneratedCode());
        request.setErrorReason(buildEvaluationFixReason(evaluation));
        request.setProblemBundle(bundle);
        request.setTargetDescription(ProblemBundleContextBuilder.workflowTargetDescription(
                bundle, sceneName, codeResult.getDescription(), outputTarget));
        request.setSceneName(sceneName);
        request.setExpectedSceneName(sceneName);
        request.setOutputTarget(outputTarget);
        String storyboardJson = narrative != null && narrative.hasStoryboard()
                ? StoryboardJsonBuilder.buildForCodegen(narrative.getStoryboard(), outputTarget)
                : StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON;
        request.setStoryboardJson(storyboardJson);
        request.setStaticAnalysisJson(toPrettyJson(evaluation.getFinalStaticAnalysis()));
        request.setReviewJson(toPrettyJson(evaluation.getFinalReview()));
        request.setInputTextHealth(TextHealthDiagnostics.summarize(
                request.getErrorReason() + "\n" + storyboardJson));
        request.setRulesPrompt(CodeEvaluationPrompts.buildRevisionRulesPrompt(outputTarget));
        request.setFixedContextPrompt(CodeEvaluationPrompts.buildRevisionFixedContextPrompt(
                bundle, request.getTargetDescription(), outputTarget));
        request.setFixHistory(buildFixHistory(fixTrace));
        return request;
    }

    private String buildEvaluationFixReason(CodeEvaluationResult evaluation) {
        List<String> reasons = new ArrayList<>();
        if (evaluation != null && evaluation.getFinalStaticAnalysis() != null
                && evaluation.getFinalStaticAnalysis().getFindings() != null) {
            for (CodeEvaluationResult.StaticFinding finding : evaluation.getFinalStaticAnalysis().getFindings()) {
                if (finding != null && "fail".equalsIgnoreCase(finding.getSeverity())) {
                    addReason(reasons, finding.getSummary());
                }
            }
        }
        CodeEvaluationResult.ReviewSnapshot review =
                evaluation != null ? evaluation.getFinalReview() : null;
        if (review != null && review.getRuleChecks() != null) {
            for (CodeEvaluationResult.RuleCheck check : review.getRuleChecks()) {
                if (isFailedRuleCheck(check)) {
                    addReason(reasons, StringUtils.hasText(check.getRequirement())
                            ? check.getRequirement()
                            : check.getRuleId());
                }
            }
        }
        if (review != null && review.getBlockingIssues() != null) {
            for (String issue : review.getBlockingIssues()) {
                addReason(reasons, issue);
            }
        }
        if (reasons.isEmpty() && evaluation != null && StringUtils.hasText(evaluation.getGateReason())) {
            addReason(reasons, evaluation.getGateReason());
        }
        return String.join("\n", reasons);
    }

    private boolean isFailedRuleCheck(CodeEvaluationResult.RuleCheck check) {
        return check != null && "fail".equalsIgnoreCase(check.getStatus());
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

    private void addReason(List<String> reasons, String reason) {
        if (!StringUtils.hasText(reason)) {
            return;
        }
        String trimmed = reason.trim();
        if (!reasons.contains(trimmed)) {
            reasons.add(trimmed);
        }
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

    private <T> T load(MathVisionTask task, StageEnum stage, Class<T> type) {
        MathVisionVersion version = currentVersion(task);
        Integer stageVersion = stage == StageEnum.PROBLEM_NORMALIZATION
                ? version.getPnVersion() : version.getVsVersion();
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
            throw new IllegalStateException("Failed to serialize JSON: " + e.getMessage(), e);
        }
    }
}
