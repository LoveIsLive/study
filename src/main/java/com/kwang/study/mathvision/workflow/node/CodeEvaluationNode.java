package com.kwang.study.mathvision.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.ai.MathVisionAiChatService;
import com.kwang.study.mathvision.workflow.model.AiContentPart;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.model.CodeEvaluationResult;
import com.kwang.study.mathvision.workflow.model.CodeResult;
import com.kwang.study.mathvision.workflow.model.Narrative;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.prompt.CodeEvaluationPrompts;
import com.kwang.study.mathvision.workflow.prompt.ToolSchemas;
import com.kwang.study.mathvision.workflow.prompt.StoryboardJsonBuilder;
import com.kwang.study.mathvision.workflow.util.GeoGebraCodeUtils;
import com.kwang.study.mathvision.workflow.util.ManimCodeUtils;
import com.kwang.study.mathvision.workflow.util.ProblemBundleContextBuilder;
import com.kwang.study.mathvision.workflow.util.SceneModeUtils;
import com.kwang.study.mathvision.workflow.util.StoryboardConstraintUtils;
import com.kwang.study.mathvision.workflow.util.StoryboardPatchResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CodeEvaluationNode {

    private static final Logger log = LoggerFactory.getLogger(CodeEvaluationNode.class);
    private static final Pattern SCENE_CLASS = Pattern.compile(
            "class\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*Scene[^)]*)\\)\\s*:");
    private static final Pattern FADE_IN_PATTERN = Pattern.compile("\\bFadeIn\\s*\\(");
    private static final Pattern FADE_OUT_PATTERN = Pattern.compile("\\bFadeOut\\s*\\(");
    private static final Pattern TRANSFORM_PATTERN =
            Pattern.compile("\\b(?:Transform|TransformMatchingTex|TransformMatchingShapes)\\s*\\(");
    private static final Pattern REPLACEMENT_TRANSFORM_PATTERN =
            Pattern.compile("\\bReplacementTransform\\s*\\(");
    private static final Pattern FADE_TRANSFORM_PATTERN = Pattern.compile("\\bFadeTransform\\s*\\(");
    private static final Pattern ANIMATE_PATTERN = Pattern.compile("\\.animate\\.");
    private static final Pattern ARRANGE_PATTERN = Pattern.compile("\\.arrange(?:_in_grid)?\\(");
    private static final Pattern NEXT_TO_PATTERN = Pattern.compile("\\.next_to\\(");
    private static final Pattern MATH_TEX_PATTERN = Pattern.compile("\\bMathTex\\s*\\(");
    private static final Pattern TEXT_PATTERN = Pattern.compile("\\bText\\s*\\(");
    private static final Pattern THREE_D_SCENE_PATTERN =
            Pattern.compile("class\\s+\\w+\\s*\\(.*?ThreeDScene.*?\\)");
    private static final Pattern THREE_D_OBJECT_PATTERN = Pattern.compile(
            "\\b(?:ThreeDAxes|Dot3D|Surface|Sphere|Cube|Prism|Cone|Cylinder|Arrow3D|Line3D|Torus|ParametricSurface|OpenGLSurface|OpenGLSurfaceMesh)\\s*\\(");

    private final MathVisionAiChatService aiChatService;
    private final ObjectMapper objectMapper;

    public CodeEvaluationNode(MathVisionAiChatService aiChatService, ObjectMapper objectMapper) {
        this.aiChatService = aiChatService;
        this.objectMapper = objectMapper;
    }

    public Result run(MathVisionTask task,
                      ProblemBundle bundle,
                      Narrative narrative,
                      CodeResult codeResult,
                      int revisionAttempts,
                      boolean revisedCodeApplied,
                      MathVisionStageExecutionContext context) {
        if (context != null) {
            context.checkCanceled();
        }
        Instant start = Instant.now();
        String configuredOutputTarget = codeResult != null ? codeResult.getOutputTarget() : null;
        String outputTarget = StringUtils.hasText(configuredOutputTarget)
                ? configuredOutputTarget
                : task.getOutputTarget();
        if (!StringUtils.hasText(outputTarget)) {
            outputTarget = "manim";
        }

        CodeEvaluationResult evaluation = new CodeEvaluationResult();
        evaluation.setTotalEvaluations(1);
        evaluation.setRevisionAttempts(revisionAttempts);
        evaluation.setRevisionTriggered(revisionAttempts > 0);
        evaluation.setRevisedCodeApplied(revisedCodeApplied);
        evaluation.setSceneName(resolveSceneName(codeResult));

        if (codeResult == null || !codeResult.hasCode()) {
            evaluation.setApprovedForRender(false);
            evaluation.setGateReason("No code available for code evaluation");
            evaluation.setExecutionTimeSeconds(secondsSince(start));
            return new Result(evaluation, 0);
        }

        String sceneMode = bundle != null ? bundle.getSceneMode() : null;
        CodeEvaluationResult.StaticAnalysis staticAnalysis = analyze(codeResult, outputTarget, narrative, sceneMode);
        CodeEvaluationResult.ReviewSnapshot review;
        int apiCalls = 0;
        if (staticAnalysis.hasBlockingFindings()) {
            review = fallbackReviewFromStaticAnalysis(staticAnalysis);
        } else {
            ReviewResult reviewResult = requestCodeReview(task, bundle, narrative, codeResult, staticAnalysis, outputTarget);
            review = reviewResult.review;
            apiCalls = reviewResult.apiCalls;
        }

        boolean approved = passesGate(staticAnalysis, review);
        evaluation.setInitialStaticAnalysis(staticAnalysis);
        evaluation.setFinalStaticAnalysis(staticAnalysis);
        evaluation.setInitialReview(review);
        evaluation.setFinalReview(review);
        evaluation.setApprovedForRender(approved);
        evaluation.setToolCalls(apiCalls);
        evaluation.setGateReason(buildGateReason(approved, staticAnalysis, review));
        evaluation.setExecutionTimeSeconds(secondsSince(start));

        CodeEvaluationResult.EvaluationAttempt attempt = new CodeEvaluationResult.EvaluationAttempt();
        attempt.setSequence(Math.max(revisionAttempts, 0) + 1);
        attempt.setApprovedForRender(approved);
        attempt.setGateReason(evaluation.getGateReason());
        attempt.setSceneName(evaluation.getSceneName());
        attempt.setStaticAnalysis(staticAnalysis);
        attempt.setReview(review);
        evaluation.getAttempts().add(attempt);

        if (approved) {
            log.info("MathVision 代码评估通过, taskId={}, sceneName={}, apiCalls={}",
                    task.getId(), evaluation.getSceneName(), apiCalls);
        } else {
            log.warn("MathVision 代码评估未通过, taskId={}, sceneName={}, reason={}",
                    task.getId(), evaluation.getSceneName(), evaluation.getGateReason());
        }
        return new Result(evaluation, apiCalls);
    }

    private CodeEvaluationResult.StaticAnalysis analyze(CodeResult codeResult,
                                                        String outputTarget,
                                                        Narrative narrative,
                                                        String sceneMode) {
        CodeEvaluationResult.StaticAnalysis analysis = new CodeEvaluationResult.StaticAnalysis();
        String code = StringUtils.hasText(codeResult.getGeneratedCode()) ? codeResult.getGeneratedCode() : "";
        String extracted = "geogebra".equalsIgnoreCase(outputTarget) ? code.trim() : ManimCodeUtils.extractCode(code);
        analysis.setCodeLines(StringUtils.hasText(extracted) ? extracted.split("\\R").length : 0);
        Narrative.Storyboard storyboard = narrative != null
                ? StoryboardPatchResolver.buildMergedStoryboard(narrative.getStoryboard())
                : null;
        if (storyboard != null && storyboard.getScenes() != null) {
            analysis.setSceneCount(storyboard.getScenes().size());
        }

        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            List<String> issues = GeoGebraCodeUtils.validateFull(extracted);
            for (String issue : issues) {
                analysis.getFindings().add(finding("static_validation", "fail", issue, issue));
            }
            for (String warning : GeoGebraCodeUtils.validateFullWarnings(extracted)) {
                analysis.getFindings().add(finding("api_whitelist_warning", "warn", warning, warning));
            }
            if (analysis.getSceneCount() == 0) {
                analysis.setSceneCount(1);
            }
            analysis.setClassName(GeoGebraCodeUtils.EXPECTED_FIGURE_NAME);
            analysis.setUsesManimImport(false);
            analysis.setHasConstruct(true);
            return analysis;
        }

        analysis.setFadeInCount(count(extracted, FADE_IN_PATTERN));
        analysis.setFadeOutCount(count(extracted, FADE_OUT_PATTERN));
        analysis.setTransformCount(count(extracted, TRANSFORM_PATTERN) + count(extracted, ANIMATE_PATTERN));
        analysis.setReplacementTransformCount(count(extracted, REPLACEMENT_TRANSFORM_PATTERN));
        analysis.setFadeTransformCount(count(extracted, FADE_TRANSFORM_PATTERN));
        analysis.setArrangeCount(count(extracted, ARRANGE_PATTERN));
        analysis.setNextToCount(count(extracted, NEXT_TO_PATTERN));
        analysis.setMathTexCount(count(extracted, MATH_TEX_PATTERN));
        analysis.setTextCount(count(extracted, TEXT_PATTERN));
        analysis.setThreeDScene(THREE_D_SCENE_PATTERN.matcher(extracted).find());
        analysis.setThreeDObjectCount(count(extracted, THREE_D_OBJECT_PATTERN));
        analysis.setThreeDStoryboardSceneCount(countThreeDStoryboardScenes(storyboard, sceneMode));
        analysis.setUsesManimImport(extracted.contains("from manim import"));
        analysis.setHasConstruct(extracted.contains("def construct("));
        Matcher matcher = SCENE_CLASS.matcher(extracted);
        int sceneClassCount = 0;
        while (matcher.find()) {
            sceneClassCount++;
            if (!StringUtils.hasText(analysis.getClassName())) {
                analysis.setClassName(matcher.group(1));
            }
        }
        if (analysis.getSceneCount() == 0) {
            analysis.setSceneCount(sceneClassCount);
        }
        for (String issue : ManimCodeUtils.validateFull(extracted)) {
            analysis.getFindings().add(finding("static_validation", "fail", issue, issue));
        }
        for (String warning : ManimCodeUtils.validateFullWarnings(extracted)) {
            analysis.getFindings().add(finding("api_whitelist_warning", "warn", warning, warning));
        }
        addStoryboardDrivenFindings(analysis, extracted, narrative, sceneMode);
        addDynamicConstraintFindings(analysis, extracted, storyboard);
        return analysis;
    }

    /**
     * Storyboard-aware static heuristics ported from the standalone math-vision workflow:
     * flag missing 3D staging and weak transform continuity relative to storyboard intent.
     */
    private void addStoryboardDrivenFindings(CodeEvaluationResult.StaticAnalysis analysis,
                                             String code,
                                             Narrative narrative,
                                             String sceneMode) {
        Narrative.Storyboard storyboard = narrative != null
                ? StoryboardPatchResolver.buildMergedStoryboard(narrative.getStoryboard())
                : null;

        boolean codeUsesThreeDScene = THREE_D_SCENE_PATTERN.matcher(code).find();
        int threeDStoryboardScenes = countThreeDStoryboardScenes(storyboard, sceneMode);
        if (threeDStoryboardScenes > 0 && !codeUsesThreeDScene) {
            String message = "The storyboard requests 3D staging, but the code does not use `ThreeDScene`.";
            analysis.getFindings().add(finding("three_d_scene_required", "fail", message,
                    String.format(Locale.ROOT, "storyboard_3d_scenes=%d, code_uses_threedscene=%s",
                            threeDStoryboardScenes, codeUsesThreeDScene)));
        }

        int continuityScenes = countContinuityScenes(storyboard);
        int transformLike = count(code, TRANSFORM_PATTERN) + count(code, ANIMATE_PATTERN)
                + count(code, REPLACEMENT_TRANSFORM_PATTERN) + count(code, FADE_TRANSFORM_PATTERN);
        int fadeCycles = count(code, FADE_IN_PATTERN) + count(code, FADE_OUT_PATTERN);
        if (continuityScenes >= 3 && transformLike == 0 && fadeCycles >= 6) {
            analysis.getFindings().add(finding("weak_transform_continuity", "info",
                    "The storyboard expects persistent visual continuity, but the code barely uses transforms.",
                    String.format(Locale.ROOT, "continuity_scenes=%d, transform_like=%d, fade_in_out=%d",
                            continuityScenes, transformLike, fadeCycles)));
        } else if (continuityScenes >= 2 && transformLike <= 1 && fadeCycles >= 4) {
            analysis.getFindings().add(finding("weak_transform_continuity", "info",
                    "The storyboard expects persistent visual continuity, but the code uses few transforms.",
                    String.format(Locale.ROOT, "continuity_scenes=%d, transform_like=%d, fade_in_out=%d",
                            continuityScenes, transformLike, fadeCycles)));
        }
    }

    /**
     * Deterministically enforces storyboard motion and attachment contracts that
     * cannot be proven by counting Manim APIs alone. This intentionally runs
     * before the LLM reviewer so a missing updater cannot silently fall back to
     * an approving static synthesis when the reviewer is unavailable.
     */
    private void addDynamicConstraintFindings(CodeEvaluationResult.StaticAnalysis analysis,
                                              String code,
                                              Narrative.Storyboard storyboard) {
        if (!StringUtils.hasText(code) || storyboard == null) {
            return;
        }

        Set<String> activelyMovedIds = collectActivelyMovedObjectIds(storyboard);
        if (activelyMovedIds.isEmpty()) {
            return;
        }

        Set<String> motionConstrainedIds = new LinkedHashSet<>();
        for (Narrative.StoryboardConstraint constraint : StoryboardConstraintUtils.allConstraints(storyboard)) {
            if (StoryboardConstraintUtils.isHard(constraint)
                    && StoryboardConstraintUtils.isMotionConstraint(constraint)) {
                motionConstrainedIds.addAll(StoryboardConstraintUtils.ownerIds(constraint));
            }
        }

        Set<String> reportedMotion = new LinkedHashSet<>();
        for (String objectId : motionConstrainedIds) {
            if (!activelyMovedIds.contains(objectId) || !reportedMotion.add(objectId)) {
                continue;
            }
            if (!hasDynamicObjectBinding(code, objectId, true)) {
                analysis.getFindings().add(finding(
                        "motion_constraint_binding",
                        "fail",
                        "Storyboard object `" + objectId
                                + "` is moved under a hard motion constraint, but its registered Manim object has no updater, always_redraw binding, UpdateFromFunc, or MoveAlongPath.",
                        "object_id=" + objectId + ", aliases=" + resolveObjectAliases(code, objectId)));
            }
        }

        Set<String> reportedAttachments = new LinkedHashSet<>();
        for (Narrative.StoryboardConstraint constraint : StoryboardConstraintUtils.allConstraints(storyboard)) {
            if (!StoryboardConstraintUtils.isHard(constraint)
                    || !StoryboardConstraintUtils.isAttachmentConstraint(constraint)) {
                continue;
            }
            Set<String> anchors = StoryboardConstraintUtils.dependencyIds(constraint);
            boolean followsMovingAnchor = anchors.stream()
                    .anyMatch(anchor -> activelyMovedIds.contains(anchor) && motionConstrainedIds.contains(anchor));
            if (!followsMovingAnchor) {
                continue;
            }
            for (String attachedId : StoryboardConstraintUtils.ownerIds(constraint)) {
                if (!reportedAttachments.add(attachedId)) {
                    continue;
                }
                if (!hasDynamicObjectBinding(code, attachedId, false)) {
                    analysis.getFindings().add(finding(
                            "dynamic_attachment_binding",
                            "fail",
                            "Storyboard attachment `" + attachedId + "` follows moving anchor(s) "
                                    + anchors + " but its registered Manim object has no updater or always_redraw binding.",
                            "attached_id=" + attachedId + ", anchors=" + anchors
                                    + ", aliases=" + resolveObjectAliases(code, attachedId)));
                }
            }
        }
    }

    private Set<String> collectActivelyMovedObjectIds(Narrative.Storyboard storyboard) {
        Set<String> ids = new LinkedHashSet<>();
        if (storyboard == null || storyboard.getScenes() == null) {
            return ids;
        }
        for (Narrative.StoryboardScene scene : storyboard.getScenes()) {
            if (scene == null || scene.getActions() == null) {
                continue;
            }
            for (Narrative.StoryboardAction action : scene.getActions()) {
                if (action == null || !StringUtils.hasText(action.getType()) || action.getTargets() == null) {
                    continue;
                }
                String type = action.getType().trim().toLowerCase(Locale.ROOT);
                if ("move".equals(type) || "motion".equals(type) || "animate".equals(type)) {
                    for (String target : action.getTargets()) {
                        if (StringUtils.hasText(target)) {
                            ids.add(target.trim());
                        }
                    }
                }
            }
        }
        return ids;
    }

    private boolean hasDynamicObjectBinding(String code, String objectId, boolean allowMoveAlongPath) {
        if (!StringUtils.hasText(code) || !StringUtils.hasText(objectId)) {
            return false;
        }
        String quotedId = Pattern.quote(objectId);
        if (Pattern.compile("(?s)(?:self\\.)?register_object\\s*\\(\\s*['\"]" + quotedId
                        + "['\"]\\s*,\\s*always_redraw\\s*\\(")
                .matcher(code).find()
                || Pattern.compile("(?s)self\\.objects\\s*\\[\\s*['\"]" + quotedId
                        + "['\"]\\s*]\\s*=\\s*always_redraw\\s*\\(")
                .matcher(code).find()
                || Pattern.compile("(?s)self\\.objects\\s*\\[\\s*['\"]" + quotedId
                        + "['\"]\\s*]\\s*\\.add_updater\\s*\\(")
                .matcher(code).find()) {
            return true;
        }

        for (String alias : resolveObjectAliases(code, objectId)) {
            String quotedAlias = Pattern.quote(alias);
            if (Pattern.compile("(?s)\\b" + quotedAlias + "\\s*=\\s*always_redraw\\s*\\(")
                    .matcher(code).find()
                    || Pattern.compile("(?s)\\b" + quotedAlias + "\\s*\\.add_updater\\s*\\(")
                    .matcher(code).find()
                    || Pattern.compile("(?s)\\bUpdateFromFunc\\s*\\(\\s*" + quotedAlias + "\\b")
                    .matcher(code).find()) {
                return true;
            }
            if (allowMoveAlongPath
                    && Pattern.compile("(?s)\\bMoveAlongPath\\s*\\(\\s*" + quotedAlias + "\\b")
                    .matcher(code).find()) {
                return true;
            }
        }
        return false;
    }

    private Set<String> resolveObjectAliases(String code, String objectId) {
        Set<String> aliases = new LinkedHashSet<>();
        if (!StringUtils.hasText(code) || !StringUtils.hasText(objectId)) {
            return aliases;
        }
        if (objectId.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            aliases.add(objectId);
        }

        String quotedId = Pattern.quote(objectId);
        collectAliasMatches(aliases, code, Pattern.compile(
                "(?s)(?:self\\.)?register_object\\s*\\(\\s*['\"]" + quotedId
                        + "['\"]\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*)"));
        collectAliasMatches(aliases, code, Pattern.compile(
                "(?s)self\\.objects\\s*\\[\\s*['\"]" + quotedId
                        + "['\"]\\s*]\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)"));
        collectAliasMatches(aliases, code, Pattern.compile(
                "(?s)([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*self\\.objects\\s*\\[\\s*['\"]"
                        + quotedId + "['\"]\\s*]"));
        return aliases;
    }

    private void collectAliasMatches(Set<String> aliases, String code, Pattern pattern) {
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            String alias = matcher.group(1);
            if (StringUtils.hasText(alias)) {
                aliases.add(alias);
            }
        }
    }

    private int countThreeDStoryboardScenes(Narrative.Storyboard storyboard, String sceneMode) {
        if (!SceneModeUtils.isThreeD(sceneMode)
                || storyboard == null
                || storyboard.getScenes() == null) {
            return 0;
        }
        return storyboard.getScenes().size();
    }

    private int countContinuityScenes(Narrative.Storyboard storyboard) {
        int count = 0;
        if (storyboard == null || storyboard.getScenes() == null) {
            return count;
        }
        for (Narrative.StoryboardScene scene : storyboard.getScenes()) {
            if (scene != null
                    && scene.getPersistentObjects() != null
                    && !scene.getPersistentObjects().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private int count(String code, Pattern pattern) {
        if (!StringUtils.hasText(code) || pattern == null) {
            return 0;
        }
        Matcher matcher = pattern.matcher(code);
        int total = 0;
        while (matcher.find()) {
            total++;
        }
        return total;
    }

    private ReviewResult requestCodeReview(MathVisionTask task,
                                           ProblemBundle bundle,
                                           Narrative narrative,
                                           CodeResult codeResult,
                                           CodeEvaluationResult.StaticAnalysis staticAnalysis,
                                           String outputTarget) {
        String sceneName = resolveSceneName(codeResult);
        String targetDescription = ProblemBundleContextBuilder.workflowTargetDescription(
                bundle, sceneName, codeResult.getDescription(), outputTarget);
        String storyboardJson = narrative != null && narrative.hasStoryboard()
                ? StoryboardJsonBuilder.buildForCodegen(narrative.getStoryboard(), outputTarget)
                : StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON;
        String staticAnalysisJson = toPrettyJson(staticAnalysis);
        try {
            JsonNode payload = aiChatService.requestJson(
                    task,
                    List.of(
                            AiMessage.system(CodeEvaluationPrompts.buildReviewRulesPrompt(outputTarget)),
                            AiMessage.system(CodeEvaluationPrompts.buildReviewFixedContextPrompt(
                                    bundle, targetDescription, outputTarget)),
                            AiMessage.user(List.of(AiContentPart.text(CodeEvaluationPrompts.reviewUserPrompt(
                                    sceneName,
                                    storyboardJson,
                                    staticAnalysisJson,
                                    codeResult.getGeneratedCode(),
                                    outputTarget))))
                    ),
                    ToolSchemas.CODE_REVIEW);
            CodeEvaluationResult.ReviewSnapshot parsed = parseReviewSnapshot(payload);
            if (parsed != null) {
                return new ReviewResult(normalizeReview(parsed), 1);
            }
            log.warn("MathVision code reviewer returned no parseable structure; using static fallback");
            return new ReviewResult(fallbackReviewFromStaticAnalysis(staticAnalysis), 1);
        } catch (Exception e) {
            log.warn("MathVision 代码 AI 评估失败, 使用静态评估兜底: {}", e.getMessage());
            return new ReviewResult(fallbackReviewFromStaticAnalysis(staticAnalysis), 0);
        }
    }

    private CodeEvaluationResult.ReviewSnapshot parseReviewSnapshot(JsonNode payload) {
        JsonNode reviewNode = unwrapReviewPayload(payload);
        if (reviewNode == null || !reviewNode.isObject()) {
            return null;
        }
        CodeEvaluationResult.ReviewSnapshot review = new CodeEvaluationResult.ReviewSnapshot();
        review.setApprovedForRender(readBoolean(reviewNode, "approved_for_render", "approvedForRender"));
        review.setRuleChecks(readRuleChecks(reviewNode, "rule_checks", "ruleChecks"));
        review.setSummary(readText(reviewNode, "summary"));
        review.setStrengths(readStringList(reviewNode, "strengths"));
        review.setBlockingIssues(readStringList(reviewNode, "blocking_issues", "blockingIssues"));
        review.setRevisionDirectives(readStringList(
                reviewNode, "revision_directives", "revisionDirectives"));
        if (review.getRuleChecks().isEmpty()
                && review.getBlockingIssues().isEmpty()
                && review.getRevisionDirectives().isEmpty()
                && !StringUtils.hasText(review.getSummary())) {
            return null;
        }
        return review;
    }

    private JsonNode unwrapReviewPayload(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        for (String field : List.of("review", "code_review", "codeReview", "result", "payload")) {
            JsonNode wrapped = payload.get(field);
            if (wrapped != null && wrapped.isObject()) {
                return wrapped;
            }
        }
        return payload;
    }

    private boolean readBoolean(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                return value.asBoolean(false);
            }
        }
        return false;
    }

    private String readText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                return value.asText("");
            }
        }
        return "";
    }

    private List<String> readStringList(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isArray()) {
                List<String> items = new ArrayList<>();
                for (JsonNode item : value) {
                    String text = item.asText("").trim();
                    if (!text.isEmpty()) {
                        items.add(text);
                    }
                }
                return items;
            }
        }
        return new ArrayList<>();
    }

    private List<CodeEvaluationResult.RuleCheck> readRuleChecks(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isArray()) {
                List<CodeEvaluationResult.RuleCheck> checks = new ArrayList<>();
                for (JsonNode item : value) {
                    if (item == null || !item.isObject()) {
                        continue;
                    }
                    CodeEvaluationResult.RuleCheck check = new CodeEvaluationResult.RuleCheck();
                    check.setRuleId(readText(item, "rule_id", "ruleId"));
                    check.setRequirement(readText(item, "requirement"));
                    check.setStatus(readText(item, "status"));
                    check.setEvidence(readText(item, "evidence"));
                    check.setSeverity(normalizeSeverityText(readText(item, "severity")));
                    if (StringUtils.hasText(check.getRuleId())
                            || StringUtils.hasText(check.getRequirement())
                            || StringUtils.hasText(check.getStatus())) {
                        checks.add(check);
                    }
                }
                return checks;
            }
        }
        return new ArrayList<>();
    }

    private CodeEvaluationResult.ReviewSnapshot normalizeReview(
            CodeEvaluationResult.ReviewSnapshot review) {
        if (!StringUtils.hasText(review.getSummary())) {
            review.setSummary("Structured rule-compliance review completed.");
        }
        review.setRuleChecks(normalizeRuleChecks(review.getRuleChecks()));
        if (review.getStrengths() == null) {
            review.setStrengths(new ArrayList<>());
        }
        if (review.getBlockingIssues() == null) {
            review.setBlockingIssues(new ArrayList<>());
        }
        if (review.getRevisionDirectives() == null) {
            review.setRevisionDirectives(new ArrayList<>());
        }
        if (hasMandatoryFailedRuleChecks(review) || !review.getBlockingIssues().isEmpty()) {
            review.setApprovedForRender(false);
        }
        return review;
    }

    private List<CodeEvaluationResult.RuleCheck> normalizeRuleChecks(
            List<CodeEvaluationResult.RuleCheck> checks) {
        List<CodeEvaluationResult.RuleCheck> normalized = new ArrayList<>();
        if (checks == null) {
            return normalized;
        }
        for (CodeEvaluationResult.RuleCheck check : checks) {
            if (check == null) {
                continue;
            }
            String ruleId = normalizeRuleText(check.getRuleId());
            String requirement = normalizeRuleText(check.getRequirement());
            if (!StringUtils.hasText(ruleId) && !StringUtils.hasText(requirement)) {
                continue;
            }
            normalized.add(new CodeEvaluationResult.RuleCheck(
                    ruleId,
                    requirement,
                    normalizeRuleStatus(check.getStatus()),
                    normalizeRuleText(check.getEvidence()),
                    normalizeSeverityText(check.getSeverity())));
        }
        return normalized;
    }

    private String normalizeRuleStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return "warn";
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        if ("passed".equals(normalized)) {
            return "pass";
        }
        if ("warning".equals(normalized)) {
            return "warn";
        }
        if ("failed".equals(normalized)
                || "failure".equals(normalized)
                || "block".equals(normalized)
                || "blocked".equals(normalized)) {
            return "fail";
        }
        if ("n/a".equals(normalized)
                || "na".equals(normalized)
                || "not applicable".equals(normalized)) {
            return "not_applicable";
        }
        if ("pass".equals(normalized)
                || "warn".equals(normalized)
                || "fail".equals(normalized)
                || "not_applicable".equals(normalized)) {
            return normalized;
        }
        return "warn";
    }

    private String normalizeRuleText(String text) {
        return text != null ? text.trim() : "";
    }

    private String normalizeSeverityText(String severity) {
        if (!StringUtils.hasText(severity)) {
            return "";
        }
        String normalized = severity.trim().toLowerCase(Locale.ROOT);
        if ("mandatory".equals(normalized)
                || "recommended".equals(normalized)
                || "advisory".equals(normalized)) {
            return normalized;
        }
        return severity.trim();
    }

    private CodeEvaluationResult.ReviewSnapshot fallbackReviewFromStaticAnalysis(
            CodeEvaluationResult.StaticAnalysis analysis) {
        CodeEvaluationResult.ReviewSnapshot review = new CodeEvaluationResult.ReviewSnapshot();
        List<String> blocking = new ArrayList<>();
        List<String> directives = new ArrayList<>();
        for (CodeEvaluationResult.StaticFinding finding : analysis.getFindings()) {
            boolean fail = "fail".equalsIgnoreCase(finding.getSeverity());
            review.getRuleChecks().add(new CodeEvaluationResult.RuleCheck(
                    finding.getRuleId(),
                    finding.getSummary(),
                    fail ? "fail" : "warn",
                    finding.getEvidence(),
                    fail ? "mandatory" : "recommended"));
            directives.add(finding.getSummary());
            if (fail) {
                blocking.add(finding.getSummary());
            }
        }
        if (review.getRuleChecks().isEmpty()) {
            review.getRuleChecks().add(new CodeEvaluationResult.RuleCheck(
                    "static_review",
                    "Static validation completed without findings.",
                    "pass",
                    "No static issue found.",
                    "mandatory"));
        }
        review.setApprovedForRender(blocking.isEmpty());
        review.setBlockingIssues(blocking);
        review.setRevisionDirectives(directives);
        review.setSummary("Stage 6 review synthesized from static validation.");
        return review;
    }

    private boolean passesGate(CodeEvaluationResult.StaticAnalysis staticAnalysis,
                               CodeEvaluationResult.ReviewSnapshot review) {
        return staticAnalysis != null
                && !staticAnalysis.hasBlockingFindings()
                && review != null
                && review.isApprovedForRender()
                && !hasMandatoryFailedRuleChecks(review)
                && (review.getBlockingIssues() == null || review.getBlockingIssues().isEmpty());
    }

    private String buildGateReason(boolean approved,
                                   CodeEvaluationResult.StaticAnalysis staticAnalysis,
                                   CodeEvaluationResult.ReviewSnapshot review) {
        if (approved) {
            return "Rule compliance review passed";
        }
        List<String> reasons = new ArrayList<>();
        if (staticAnalysis != null && staticAnalysis.getFindings() != null) {
            for (CodeEvaluationResult.StaticFinding finding : staticAnalysis.getFindings()) {
                if ("fail".equalsIgnoreCase(finding.getSeverity())) {
                    addReason(reasons, finding.getSummary());
                }
            }
        }
        if (review != null && review.getRuleChecks() != null) {
            for (CodeEvaluationResult.RuleCheck check : review.getRuleChecks()) {
                if (isFailedRuleCheck(check) && isMandatorySeverity(check)) {
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
        if (reasons.isEmpty() && review != null && StringUtils.hasText(review.getSummary())) {
            addReason(reasons, review.getSummary());
        }
        if (reasons.isEmpty()) {
            return "Rule compliance review recommends revisions before render.";
        }
        return String.join("; ", reasons.subList(0, Math.min(3, reasons.size())));
    }

    private boolean hasMandatoryFailedRuleChecks(CodeEvaluationResult.ReviewSnapshot review) {
        if (review == null || review.getRuleChecks() == null) {
            return false;
        }
        return review.getRuleChecks().stream()
                .anyMatch(check -> isFailedRuleCheck(check) && isMandatorySeverity(check));
    }

    private boolean isFailedRuleCheck(CodeEvaluationResult.RuleCheck check) {
        return check != null && "fail".equalsIgnoreCase(check.getStatus());
    }

    private boolean isMandatorySeverity(CodeEvaluationResult.RuleCheck check) {
        if (check == null) {
            return false;
        }
        return !StringUtils.hasText(check.getSeverity())
                || "mandatory".equalsIgnoreCase(check.getSeverity().trim());
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

    private CodeEvaluationResult.StaticFinding finding(String ruleId, String severity, String summary, String evidence) {
        return new CodeEvaluationResult.StaticFinding(ruleId, severity, summary, evidence);
    }

    private String resolveSceneName(CodeResult codeResult) {
        if (codeResult == null) {
            return ManimCodeUtils.EXPECTED_SCENE_NAME;
        }
        if (codeResult.isGeoGebraTarget()) {
            return StringUtils.hasText(codeResult.getSceneName())
                    ? codeResult.getSceneName()
                    : GeoGebraCodeUtils.EXPECTED_FIGURE_NAME;
        }
        return ManimCodeUtils.extractSceneName(codeResult.getGeneratedCode(), codeResult.getSceneName());
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private double secondsSince(Instant start) {
        return Duration.between(start, Instant.now()).toMillis() / 1000.0D;
    }

    private static final class ReviewResult {
        private final CodeEvaluationResult.ReviewSnapshot review;
        private final int apiCalls;

        private ReviewResult(CodeEvaluationResult.ReviewSnapshot review, int apiCalls) {
            this.review = review;
            this.apiCalls = apiCalls;
        }
    }

    public static final class Result {
        private final CodeEvaluationResult evaluationResult;
        private final int apiCalls;

        private Result(CodeEvaluationResult evaluationResult, int apiCalls) {
            this.evaluationResult = evaluationResult;
            this.apiCalls = apiCalls;
        }

        public CodeEvaluationResult getEvaluationResult() {
            return evaluationResult;
        }

        public int getApiCalls() {
            return apiCalls;
        }
    }
}
