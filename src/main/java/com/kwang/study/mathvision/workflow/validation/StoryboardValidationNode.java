package com.kwang.study.mathvision.workflow.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.ai.MathVisionAiChatService;
import com.kwang.study.mathvision.workflow.model.AiContentPart;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.model.KnowledgeGraph;
import com.kwang.study.mathvision.workflow.model.KnowledgeNode;
import com.kwang.study.mathvision.workflow.model.Narrative;
import com.kwang.study.mathvision.workflow.model.Narrative.Storyboard;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardAction;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardCoordinateBounds;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardCoordinateBoundsAxis;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardConstraint;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardObject;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardPlacement;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardPlacementAxis;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardScene;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardStyle;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.StoryboardValidationReport;
import com.kwang.study.mathvision.workflow.model.StoryboardValidationTraceEntry;
import com.kwang.study.mathvision.workflow.prompt.NarrativePrompts;
import com.kwang.study.mathvision.workflow.prompt.SystemPrompts;
import com.kwang.study.mathvision.workflow.prompt.ToolSchemas;
import com.kwang.study.mathvision.workflow.util.CoordinateBoundsUtils;
import com.kwang.study.mathvision.workflow.util.ProblemBundleContextBuilder;
import com.kwang.study.mathvision.workflow.util.SceneModeUtils;
import com.kwang.study.mathvision.workflow.util.StoryboardNormalizer;
import com.kwang.study.mathvision.workflow.util.StoryboardConstraintCatalog;
import com.kwang.study.mathvision.workflow.util.StoryboardConstraintUtils;
import com.kwang.study.mathvision.workflow.util.StoryboardGeometricMarkerValidator;
import com.kwang.study.mathvision.workflow.util.StoryboardPatchResolver;
import com.kwang.study.mathvision.workflow.util.TargetDescriptionBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class StoryboardValidationNode {

    private static final int DEFAULT_MAX_VALIDATION_FIX_ATTEMPTS = 5;
    private static final int DEFAULT_MAX_PLACEMENT_ENRICHMENT_RETRIES = 3;
    private static final Pattern ASCII_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final double NON_TEXT_CONTRAST_THRESHOLD = 3.0;
    private static final double TEXT_CONTRAST_THRESHOLD = 4.5;
    private static final String DEFAULT_STORYBOARD_BACKGROUND_DARK = "#000000";
    private static final String DEFAULT_STORYBOARD_BACKGROUND_LIGHT = "#FFFFFF";
    private static final double FALLBACK_FRAME_MIN_X = -7.111111D;
    private static final double FALLBACK_FRAME_MAX_X = 7.111111D;
    private static final double FALLBACK_FRAME_MIN_Y = -4.0D;
    private static final double FALLBACK_FRAME_MAX_Y = 4.0D;
    private static final double OFFSCREEN_TOLERANCE = 0.03D;
    private static final double MIN_OVERLAP_AREA = 0.015D;
    private static final double MIN_OVERLAP_RATIO = 0.08D;
    private static final double SPATIAL_BUCKET_SIZE = 1.25D;

    private final MathVisionAiChatService aiChatService;
    private final ObjectMapper objectMapper;
    private final MathVisionModelCatalog modelCatalog;

    public StoryboardValidationNode(MathVisionAiChatService aiChatService,
                                    ObjectMapper objectMapper,
                                    MathVisionModelCatalog modelCatalog) {
        this.aiChatService = aiChatService;
        this.objectMapper = objectMapper;
        this.modelCatalog = modelCatalog;
    }

    public Result run(MathVisionTask task,
                      ProblemBundle bundle,
                      KnowledgeGraph graph,
                      Narrative narrative,
                      MathVisionStageExecutionContext context) {
        Counter apiCalls = new Counter();
        String outputTarget = task.getOutputTarget();
        String sceneMode = SceneModeUtils.normalize(bundle != null ? bundle.getSceneMode() : null);
        if (narrative == null || narrative.getStoryboard() == null) {
            StoryboardValidationReport report = buildSkippedReport("No narrative/storyboard to validate", outputTarget);
            return new Result(narrative, report, apiCalls.value);
        }

        Narrative current = copyNarrative(narrative);
        normalizeStoryboard(current.getStoryboard(), sceneMode);
        int originalSceneCount = sceneCount(current.getStoryboard());
        Instant initialStart = Instant.now();
        List<String> issues = validate(task, bundle, graph, current.getStoryboard(), sceneMode, apiCalls, context);
        StoryboardValidationReport report = baseReport(current.getStoryboard(), issues, outputTarget);
        appendTrace(report, current.getStoryboard(), "initial_validation", 0, false, false,
                issues, 0, secondsSince(initialStart), "Initial storyboard validation");

        boolean fixApplied = false;
        int attempts = 0;
        List<RollingTurn> cleanupTurns = new ArrayList<>();

        if (issues.isEmpty()) {
            attempts++;
            Instant cleanupStart = Instant.now();
            int before = apiCalls.value;
            Narrative fixed = attemptLlmFix(task, bundle, graph, current, issues, cleanupTurns,
                    outputTarget, sceneMode, apiCalls);
            int cleanupCalls = apiCalls.value - before;
            if (fixed == null || fixed.getStoryboard() == null) {
                appendTrace(report, current.getStoryboard(), "cleanup_failed", attempts, true, false,
                        issues, cleanupCalls, secondsSince(cleanupStart),
                        "Storyboard validation passed, but optional cleanup returned no usable storyboard");
                finalizeReport(report, true, true, false, List.of(),
                        "Storyboard validation passed; optional cleanup was skipped after an unusable response");
                return new Result(current, report, apiCalls.value);
            }
            normalizeStoryboard(fixed.getStoryboard(), sceneMode);
            int fixedSceneCount = sceneCount(fixed.getStoryboard());
            if (fixedSceneCount != originalSceneCount) {
                List<String> sceneCountIssues = List.of(sceneCountMismatchIssue(originalSceneCount, fixedSceneCount));
                appendTrace(report, fixed.getStoryboard(), "cleanup_rejected_scene_count_mismatch",
                        attempts, true, false, sceneCountIssues, cleanupCalls,
                        secondsSince(cleanupStart), "Optional cleanup was rejected because scene count changed");
                issues = sceneCountIssues;
            } else {
                current = fixed;
                fixApplied = true;
                issues = validate(task, bundle, graph, current.getStoryboard(), sceneMode, apiCalls, context);
                appendTrace(report, current.getStoryboard(), "post_cleanup_validation", attempts, true,
                        true, issues, cleanupCalls, secondsSince(cleanupStart),
                        issues.isEmpty()
                                ? "Optional cleanup preserved a valid storyboard"
                                : "Optional cleanup introduced storyboard validation issues");
                if (issues.isEmpty()) {
                    finalizeReport(report, true, true, true, issues,
                            "Storyboard validation passed and optional cleanup completed successfully");
                    return new Result(current, report, apiCalls.value);
                }
            }
        }

        int maxValidationFixAttempts = maxValidationFixAttempts();
        while (!issues.isEmpty() && attempts < maxValidationFixAttempts) {
            context.checkCanceled();
            attempts++;
            Instant cleanupStart = Instant.now();
            int before = apiCalls.value;
            Narrative fixed = attemptLlmFix(task, bundle, graph, current, issues, cleanupTurns,
                    outputTarget, sceneMode, apiCalls);
            int cleanupCalls = apiCalls.value - before;
            if (fixed == null || fixed.getStoryboard() == null) {
                appendTrace(report, current.getStoryboard(), "cleanup_failed", attempts, true,
                        fixApplied, issues, cleanupCalls, secondsSince(cleanupStart),
                        "LLM storyboard cleanup did not return a usable storyboard");
                finalizeReport(report, false, true, fixApplied, issues,
                        "Storyboard validation found issues and automatic cleanup did not succeed");
                return new Result(current, report, apiCalls.value);
            }

            normalizeStoryboard(fixed.getStoryboard(), sceneMode);
            int fixedSceneCount = sceneCount(fixed.getStoryboard());
            if (fixedSceneCount != originalSceneCount) {
                List<String> sceneCountIssues = List.of(sceneCountMismatchIssue(originalSceneCount, fixedSceneCount));
                appendTrace(report, fixed.getStoryboard(), "cleanup_rejected_scene_count_mismatch",
                        attempts, true, false, sceneCountIssues, cleanupCalls,
                        secondsSince(cleanupStart), "Cleanup was rejected because scene count changed");
                issues = sceneCountIssues;
                continue;
            }

            current = fixed;
            fixApplied = true;
            issues = validate(task, bundle, graph, current.getStoryboard(), sceneMode, apiCalls, context);
            appendTrace(report, current.getStoryboard(), "post_cleanup_validation", attempts, true,
                    true, issues, cleanupCalls, secondsSince(cleanupStart),
                    issues.isEmpty()
                            ? "Cleanup resolved all storyboard validation issues"
                            : "Cleanup left storyboard validation issues");
        }

        finalizeReport(report, issues.isEmpty(), attempts > 0, fixApplied, issues,
                issues.isEmpty()
                        ? "Storyboard validation completed successfully"
                        : "Storyboard validation reached cleanup limit; proceeding with remaining issues");
        return new Result(current, report, apiCalls.value);
    }

    private List<String> validate(MathVisionTask task,
                                  ProblemBundle bundle,
                                  KnowledgeGraph graph,
                                  Storyboard storyboard,
                                  String sceneMode,
                                  Counter apiCalls,
                                  MathVisionStageExecutionContext context) {
        List<String> issues = new ArrayList<>();
        if (storyboard == null || storyboard.getScenes() == null || storyboard.getScenes().isEmpty()) {
            issues.add("Storyboard has no scenes");
            return issues;
        }
        issues.addAll(validateSceneModeContract(storyboard, sceneMode));
        issues.addAll(validateCoordinateBoundsContract(storyboard, sceneMode));

        Storyboard placementEnriched = resolvePlacementEnrichedStoryboard(task, bundle, graph, storyboard,
                sceneMode, apiCalls, context);
        List<StoryboardScene> layoutScenes = buildValidationLayoutScenes(
                placementEnriched != null ? placementEnriched : storyboard);
        expandCoordinateBoundsToFitPlacements(storyboard, layoutScenes, sceneMode);

        for (int i = 0; i < storyboard.getScenes().size(); i++) {
            StoryboardScene scene = storyboard.getScenes().get(i);
            String label = "scene " + (i + 1) + " (" + (scene != null ? scene.getSceneId() : null) + ")";
            if (scene == null) {
                issues.add(label + ": scene is null");
                continue;
            }
            if (!StringUtils.hasText(scene.getTitle())) {
                issues.add(label + ": missing title");
            }
            if (!StringUtils.hasText(scene.getGoal())) {
                issues.add(label + ": missing goal");
            }
            StoryboardScene layoutScene = i < layoutScenes.size() ? layoutScenes.get(i) : null;
            validateSceneLayout(label, storyboard, layoutScene, issues);
        }

        issues.addAll(validateStoryboardObjectStructure(storyboard));
        issues.addAll(validateIdentifierAscii(storyboard));
        issues.addAll(validateStoryboardColors(storyboard, task != null ? task.getOutputTarget() : null));
        issues.addAll(validateStructuredConstraints(storyboard));
        issues.addAll(validateGeometricMarkerDefinitions(storyboard));
        return issues;
    }

    private Storyboard resolvePlacementEnrichedStoryboard(MathVisionTask task,
                                                          ProblemBundle bundle,
                                                          KnowledgeGraph graph,
                                                          Storyboard storyboard,
                                                          String sceneMode,
                                                          Counter apiCalls,
                                                          MathVisionStageExecutionContext context) {
        try {
            if (!hasVisibleObjectsNeedingPlacement(storyboard)) {
                return null;
            }
            String storyboardJson = objectMapper.writeValueAsString(storyboard);
            String fixedContext = NarrativePrompts.buildFixedContextPrompt(
                    bundle,
                    "Resolve validation-only placements while preserving ProblemBundle geometry and storyboard semantics.",
                    task.getOutputTarget(),
                    buildChainSummary(graph, storyboard));
            String userPrompt = NarrativePrompts.buildPlacementEnrichmentUserPrompt(storyboardJson);
            List<String> lastIssues = List.of();
            List<RollingTurn> enrichmentTurns = new ArrayList<>();
            int maxPlacementRetries = maxPlacementEnrichmentRetries();
            for (int attempt = 1; attempt <= maxPlacementRetries + 1; attempt++) {
                context.checkCanceled();
                List<AiMessage> messages = new ArrayList<>();
                messages.add(AiMessage.system(NarrativePrompts.PLACEMENT_ENRICHMENT_SYSTEM_PROMPT));
                messages.add(AiMessage.system(fixedContext));
                for (RollingTurn turn : enrichmentTurns) {
                    messages.add(AiMessage.user(List.of(AiContentPart.text(turn.userPrompt))));
                    messages.add(new AiMessage("assistant", List.of(AiContentPart.text(turn.assistantText))));
                }
                String currentRequest = SystemPrompts.buildCurrentRequestSection(userPrompt);
                messages.add(AiMessage.user(List.of(AiContentPart.text(currentRequest))));
                JsonNode payload = aiChatService.requestJson(
                        task,
                        messages,
                        ToolSchemas.placementPatches(sceneMode));
                apiCalls.increment();
                appendRollingTurn(enrichmentTurns, currentRequest, toPrettyJson(payload),
                        placementEnrichmentConversationRounds());
                PlacementPatchMergeResult mergeResult = applyPlacementEnrichmentPatches(storyboard, payload, sceneMode);
                lastIssues = mergeResult.issues;
                if (lastIssues.isEmpty() && mergeResult.storyboard != null) {
                    return mergeResult.storyboard;
                }
                if (attempt <= maxPlacementRetries) {
                    userPrompt = buildPlacementEnrichmentRetryPrompt(lastIssues);
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String buildPlacementEnrichmentRetryPrompt(List<String> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("The previous placement-enrichment response was rejected.\n")
                .append("Return only compact JSON with top-level `placement_patches`.\n")
                .append("Each patch must include `scene_id`, `object_id`, and a complete `placement` with x/y data.\n")
                .append("Do not return full storyboard JSON, object_registry, scenes, titles, narration, content, actions, or explanatory prose.\n")
                .append("Only patch visible entering/persistent scene objects that currently lack placement.\n\n")
                .append("Issues to fix:\n");
        if (issues == null || issues.isEmpty()) {
            sb.append("- Unknown placement enrichment validation issue\n");
            return sb.toString();
        }
        int count = Math.min(issues.size(), 40);
        for (int i = 0; i < count; i++) {
            sb.append("- ").append(issues.get(i)).append("\n");
        }
        if (issues.size() > count) {
            sb.append("- ... ").append(issues.size() - count).append(" more issue(s)\n");
        }
        return sb.toString();
    }

    private Narrative attemptLlmFix(MathVisionTask task,
                                    ProblemBundle bundle,
                                    KnowledgeGraph graph,
                                    Narrative narrative,
                                    List<String> issues,
                                    List<RollingTurn> cleanupTurns,
                                    String outputTarget,
                                    String sceneMode,
                                    Counter apiCalls) {
        try {
            String storyboardJson = objectMapper.writeValueAsString(narrative.getStoryboard());
            String currentRequest = SystemPrompts.buildCurrentRequestSection(
                    NarrativePrompts.buildCleanupUserPrompt(storyboardJson, issues));
            List<AiMessage> messages = new ArrayList<>();
            messages.add(AiMessage.system(NarrativePrompts.buildRulesPrompt(outputTarget)
                    + "\n\n" + NarrativePrompts.buildRepairRules(outputTarget)));
            messages.add(AiMessage.system(NarrativePrompts.buildFixedContextPrompt(
                    bundle,
                    narrative.getTargetDescription(),
                    outputTarget,
                    buildChainSummary(graph, narrative.getStoryboard()))));
            for (RollingTurn turn : cleanupTurns) {
                messages.add(AiMessage.user(List.of(AiContentPart.text(turn.userPrompt))));
                messages.add(new AiMessage("assistant", List.of(AiContentPart.text(turn.assistantText))));
            }
            messages.add(AiMessage.user(List.of(AiContentPart.text(currentRequest))));
            JsonNode payload = aiChatService.requestJson(
                    task,
                    messages,
                    ToolSchemas.storyboard(outputTarget, sceneMode));
            apiCalls.increment();
            appendRollingTurn(cleanupTurns, currentRequest, toPrettyJson(payload),
                    storyboardCleanupConversationRounds());
            JsonNode storyboardNode = payload.has("storyboard") ? payload.get("storyboard") : payload;
            Storyboard fixedStoryboard = objectMapper.treeToValue(storyboardNode, Storyboard.class);
            normalizeStoryboard(fixedStoryboard, sceneMode);
            preserveStepRefs(narrative.getStoryboard(), fixedStoryboard);
            return new Narrative(narrative.getTargetDescription(), fixedStoryboard);
        } catch (Exception e) {
            return null;
        }
    }

    private void appendRollingTurn(List<RollingTurn> turns,
                                   String userPrompt,
                                   String assistantText,
                                   int maxRounds) {
        turns.add(new RollingTurn(userPrompt, assistantText));
        while (turns.size() > Math.max(maxRounds, 1)) {
            turns.remove(0);
        }
    }

    private int maxValidationFixAttempts() {
        Integer configured = workflow() != null
                ? workflow().getStoryboardValidationMaxRetries()
                : null;
        return configured != null ? Math.max(configured, 0) : DEFAULT_MAX_VALIDATION_FIX_ATTEMPTS;
    }

    private int maxPlacementEnrichmentRetries() {
        Integer configured = workflow() != null
                ? workflow().getPlacementEnrichmentMaxRetries()
                : null;
        return configured != null ? Math.max(configured, 0) : DEFAULT_MAX_PLACEMENT_ENRICHMENT_RETRIES;
    }

    private int storyboardCleanupConversationRounds() {
        Integer configured = workflow() != null
                ? workflow().getStoryboardCleanupConversationRounds()
                : null;
        return configured != null ? Math.max(configured, 1) : 8;
    }

    private int placementEnrichmentConversationRounds() {
        Integer configured = workflow() != null
                ? workflow().getPlacementEnrichmentConversationRounds()
                : null;
        return configured != null ? Math.max(configured, 1) : 4;
    }

    private MathVisionModelCatalog.WorkflowCatalog workflow() {
        return modelCatalog != null ? modelCatalog.getWorkflow() : null;
    }

    private PlacementPatchMergeResult applyPlacementEnrichmentPatches(Storyboard sourceStoryboard,
                                                                      JsonNode payload,
                                                                      String sceneMode) throws Exception {
        List<String> issues = new ArrayList<>();
        JsonNode patches = payload != null ? payload.get("placement_patches") : null;
        if (patches == null || !patches.isArray()) {
            return PlacementPatchMergeResult.rejected(List.of("placement enrichment response must contain array `placement_patches`"));
        }

        Storyboard enriched = copyStoryboard(sourceStoryboard);
        normalizeStoryboard(enriched, sceneMode);
        Map<String, StoryboardScene> scenes = scenesById(enriched);
        Set<String> occurrences = new LinkedHashSet<>();
        int applied = 0;

        for (int i = 0; i < patches.size(); i++) {
            JsonNode patchNode = patches.get(i);
            String label = "placement_patches[" + i + "]";
            if (patchNode == null || !patchNode.isObject()) {
                issues.add(label + ": patch must be an object");
                continue;
            }
            String sceneId = textField(patchNode, "scene_id");
            String objectId = textField(patchNode, "object_id");
            if (!StringUtils.hasText(sceneId)) {
                issues.add(label + ": missing scene_id");
                continue;
            }
            if (!StringUtils.hasText(objectId)) {
                issues.add(label + ": missing object_id");
                continue;
            }
            String key = sceneId + "\n" + objectId;
            if (!occurrences.add(key)) {
                issues.add(label + ": duplicate patch for scene '" + sceneId + "' object '" + objectId + "'");
                continue;
            }
            StoryboardScene scene = scenes.get(sceneId);
            if (scene == null) {
                issues.add(label + ": unknown scene_id '" + sceneId + "'");
                continue;
            }
            StoryboardObject target = findVisiblePatch(scene, objectId);
            if (target == null) {
                issues.add(label + ": object '" + objectId + "' is not visible in scene '" + sceneId + "'");
                continue;
            }
            if (target.getPlacement() != null && target.getPlacement().hasData()) {
                issues.add(label + ": object '" + objectId + "' already has placement");
                continue;
            }
            JsonNode placementNode = patchNode.get("placement");
            if (placementNode == null || !placementNode.isObject()) {
                issues.add(label + ": missing placement object");
                continue;
            }
            StoryboardPlacement placement = objectMapper.treeToValue(placementNode, StoryboardPlacement.class);
            List<String> placementIssues = validatePlacementPatchPlacement(label, sceneId, objectId, placement, sceneMode);
            if (!placementIssues.isEmpty()) {
                issues.addAll(placementIssues);
                continue;
            }
            target.setPlacement(placement);
            applied++;
        }
        if (!issues.isEmpty()) {
            return PlacementPatchMergeResult.rejected(issues);
        }
        if (applied == 0) {
            return PlacementPatchMergeResult.rejected(List.of("placement enrichment response contained no usable patches"));
        }
        return PlacementPatchMergeResult.accepted(enriched);
    }

    private List<String> validatePlacementPatchPlacement(String label,
                                                         String sceneId,
                                                         String objectId,
                                                         StoryboardPlacement placement,
                                                         String sceneMode) {
        List<String> issues = new ArrayList<>();
        if (placement == null || !placement.hasData()) {
            issues.add(label + ": object '" + objectId + "' in scene '" + sceneId + "' placement has no data");
            return issues;
        }
        if (StringUtils.hasText(placement.getPositioning())) {
            String positioning = placement.getPositioning().trim().toLowerCase(Locale.ROOT);
            if (!StoryboardPlacement.POSITIONING_ABSOLUTE.equals(positioning)
                    && !StoryboardPlacement.POSITIONING_RELATIVE.equals(positioning)) {
                issues.add(label + ": placement.positioning must be absolute or relative");
            }
        }
        if (placement.getX() == null || !placement.getX().hasData()) {
            issues.add(label + ": placement.x is required");
        }
        if (placement.getY() == null || !placement.getY().hasData()) {
            issues.add(label + ": placement.y is required");
        }
        if (!SceneModeUtils.isThreeD(sceneMode) && placement.getZ() != null && placement.getZ().hasData()) {
            issues.add(label + ": placement.z is forbidden for 2D scene_mode");
        }
        return issues;
    }

    private List<String> validateSceneModeContract(Storyboard storyboard, String sceneMode) {
        List<String> issues = new ArrayList<>();
        boolean threeD = SceneModeUtils.isThreeD(sceneMode);
        if (!threeD && storyboard.getCoordinateBounds() != null && storyboard.getCoordinateBounds().getZ() != null) {
            issues.add("2D ProblemBundle scene_mode forbids storyboard.coordinate_bounds.z");
        }
        for (int i = 0; i < safe(storyboard.getScenes()).size(); i++) {
            StoryboardScene scene = storyboard.getScenes().get(i);
            String label = "scene " + (i + 1) + " (" + scene.getSceneId() + ")";
            if (!threeD) {
                collectForbiddenZPlacementIssues(label + ".entering_objects", scene.getEnteringObjects(), issues);
                collectForbiddenZPlacementIssues(label + ".persistent_objects", scene.getPersistentObjects(), issues);
            }
        }
        return issues;
    }

    private void collectForbiddenZPlacementIssues(String label, List<StoryboardObject> objects, List<String> issues) {
        for (StoryboardObject object : safe(objects)) {
            if (object != null && object.getPlacement() != null
                    && object.getPlacement().getZ() != null
                    && object.getPlacement().getZ().hasData()) {
                issues.add(label + " object '" + object.getId()
                        + "': 2D scene_mode forbids placement.z; use style.z_index for layers");
            }
        }
    }

    private List<String> validateCoordinateBoundsContract(Storyboard storyboard, String sceneMode) {
        List<String> issues = new ArrayList<>();
        StoryboardCoordinateBounds bounds = storyboard.getCoordinateBounds();
        if (bounds == null) {
            issues.add("Storyboard is missing required top-level coordinate_bounds");
            return issues;
        }
        collectBoundsAxisIssues("storyboard.coordinate_bounds.x", bounds.getX(), issues);
        collectBoundsAxisIssues("storyboard.coordinate_bounds.y", bounds.getY(), issues);
        if (SceneModeUtils.isThreeD(sceneMode)) {
            collectBoundsAxisIssues("storyboard.coordinate_bounds.z", bounds.getZ(), issues);
        }
        return issues;
    }

    private void collectBoundsAxisIssues(String label,
                                         StoryboardCoordinateBoundsAxis axis,
                                         List<String> issues) {
        if (axis == null || axis.getMin() == null || axis.getMax() == null) {
            issues.add(label + " must include numeric min and max");
        }
    }

    private List<String> validateStoryboardObjectStructure(Storyboard storyboard) {
        List<String> issues = new ArrayList<>();
        Set<String> registryIds = new LinkedHashSet<>();
        Set<String> duplicateIds = new LinkedHashSet<>();
        for (int i = 0; i < safe(storyboard.getObjectRegistry()).size(); i++) {
            StoryboardObject object = storyboard.getObjectRegistry().get(i);
            String id = objectId(object);
            if (!StringUtils.hasText(id)) {
                issues.add("object_registry[" + i + "]: missing id");
                continue;
            }
            if (!registryIds.add(id)) {
                duplicateIds.add(id);
            }
            if (!StringUtils.hasText(object.getKind())) {
                issues.add("object_registry object '" + id + "': missing kind");
            }
            validatePlacementPositioning("object_registry object '" + id + "'", object.getPlacement(), issues);
        }
        for (String id : duplicateIds) {
            issues.add("object_registry: duplicate object id '" + id + "'");
        }
        for (int i = 0; i < safe(storyboard.getScenes()).size(); i++) {
            StoryboardScene scene = storyboard.getScenes().get(i);
            String label = "scene " + (i + 1) + " (" + scene.getSceneId() + ")";
            validatePatchReferences(label, "entering_objects", scene.getEnteringObjects(), registryIds, issues);
            validatePatchReferences(label, "persistent_objects", scene.getPersistentObjects(), registryIds, issues);
            validatePatchReferences(label, "exiting_objects", scene.getExitingObjects(), registryIds, issues);
            validateActionTargets(label, scene.getActions(), registryIds, issues);
        }
        return issues;
    }

    private void validatePatchReferences(String sceneLabel,
                                         String field,
                                         List<StoryboardObject> patches,
                                         Set<String> registryIds,
                                         List<String> issues) {
        Set<String> seen = new LinkedHashSet<>();
        int index = 0;
        for (StoryboardObject patch : safe(patches)) {
            String id = objectId(patch);
            String label = sceneLabel + " " + field + "[" + index + "]";
            if (!StringUtils.hasText(id)) {
                issues.add(label + ": missing id");
                index++;
                continue;
            }
            if (!seen.add(id)) {
                issues.add(label + ": duplicate object id '" + id + "' in " + field);
            }
            if (!registryIds.contains(id)) {
                issues.add(label + ": references unknown object_registry id '" + id + "'");
            }
            validatePlacementPositioning(label, patch.getPlacement(), issues);
            index++;
        }
    }

    private void validateActionTargets(String sceneLabel,
                                       List<StoryboardAction> actions,
                                       Set<String> registryIds,
                                       List<String> issues) {
        int index = 0;
        for (StoryboardAction action : safe(actions)) {
            for (String target : safe(action.getTargets())) {
                if (!StringUtils.hasText(target)) {
                    issues.add(sceneLabel + " actions[" + index + "]: blank target id");
                } else if (!registryIds.contains(target.trim())) {
                    issues.add(sceneLabel + " actions[" + index + "]: target references unknown object id '" + target + "'");
                }
            }
            index++;
        }
    }

    private void validatePlacementPositioning(String label, StoryboardPlacement placement, List<String> issues) {
        if (placement == null || !StringUtils.hasText(placement.getPositioning())) {
            return;
        }
        String positioning = placement.getPositioning().trim().toLowerCase(Locale.ROOT);
        if (!StoryboardPlacement.POSITIONING_ABSOLUTE.equals(positioning)
                && !StoryboardPlacement.POSITIONING_RELATIVE.equals(positioning)) {
            issues.add(label + ": placement.positioning must be absolute or relative");
        }
    }

    private List<String> validateIdentifierAscii(Storyboard storyboard) {
        List<String> issues = new ArrayList<>();
        for (StoryboardObject object : safe(storyboard.getObjectRegistry())) {
            validateIdentifier("object_registry id", object.getId(), issues);
            for (StoryboardConstraint constraint : safe(object.getConstraints())) {
                validateIdentifier("constraint id", constraint.getId(), issues);
                validateAsciiMap("constraint refs", constraint.getRefs(), issues);
            }
        }
        for (StoryboardScene scene : safe(storyboard.getScenes())) {
            validateIdentifier("scene_id", scene.getSceneId(), issues);
            validatePatchIds(scene.getEnteringObjects(), "entering_objects", issues);
            validatePatchIds(scene.getPersistentObjects(), "persistent_objects", issues);
            validatePatchIds(scene.getExitingObjects(), "exiting_objects", issues);
            for (StoryboardAction action : safe(scene.getActions())) {
                for (String target : safe(action.getTargets())) {
                    validateIdentifier("action target", target, issues);
                }
            }
        }
        return issues;
    }

    private void validatePatchIds(List<StoryboardObject> patches, String label, List<String> issues) {
        for (StoryboardObject patch : safe(patches)) {
            validateIdentifier(label + " id", patch.getId(), issues);
        }
    }

    private void validateIdentifier(String label, String value, List<String> issues) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (!ASCII_IDENTIFIER.matcher(value.trim()).matches()) {
            issues.add(label + " must be ASCII identifier-compatible: '" + value + "'");
        }
    }

    private void validateAsciiMap(String label, Map<String, Object> map, List<String> issues) {
        if (map == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            validateIdentifier(label + " key", entry.getKey(), issues);
            Object value = entry.getValue();
            if (value instanceof String) {
                validateIdentifier(label + " value", (String) value, issues);
            }
        }
    }

    private List<String> validateStoryboardColors(Storyboard storyboard, String outputTarget) {
        List<String> issues = new ArrayList<>();
        Map<String, StoryboardObject> registryDefinitions = new LinkedHashMap<>();
        for (StoryboardObject object : safe(storyboard.getObjectRegistry())) {
            String id = objectId(object);
            if (id != null) {
                registryDefinitions.put(id, object);
            }
        }
        for (StoryboardObject object : safe(storyboard.getObjectRegistry())) {
            validateObjectColors("object_registry object '" + objectId(object) + "'", object, null, outputTarget, issues);
        }
        for (StoryboardScene scene : safe(storyboard.getScenes())) {
            validatePatchColors(scene.getSceneId() + ".entering_objects",
                    scene.getEnteringObjects(), registryDefinitions, outputTarget, issues);
            validatePatchColors(scene.getSceneId() + ".persistent_objects",
                    scene.getPersistentObjects(), registryDefinitions, outputTarget, issues);
        }
        return issues;
    }

    private void validatePatchColors(String label,
                                     List<StoryboardObject> patches,
                                     Map<String, StoryboardObject> registryDefinitions,
                                     String outputTarget,
                                     List<String> issues) {
        for (StoryboardObject patch : safe(patches)) {
            validateObjectColors(label + " object '" + objectId(patch) + "'", patch, registryDefinitions, outputTarget, issues);
        }
    }

    private void validateObjectColors(String label,
                                      StoryboardObject object,
                                      Map<String, StoryboardObject> registryDefinitions,
                                      String outputTarget,
                                      List<String> issues) {
        if (object == null || object.getStyle() == null) {
            return;
        }
        StoryboardObject colorObject = mergeColorValidationObject(object, registryDefinitions);
        List<ColorReference> colors = collectColorReferences(colorObject);
        for (ColorReference color : colors) {
            if (!isSixDigitHexColor(color.value)) {
                issues.add(label + ".style." + color.propertyPath + " must be #RRGGBB color: '" + color.value + "'");
            }
        }
        validateObjectColorContrast(label, colorObject, colors, outputTarget, issues);
    }

    private StoryboardObject mergeColorValidationObject(StoryboardObject object,
                                                        Map<String, StoryboardObject> registryDefinitions) {
        if (object == null || registryDefinitions == null || registryDefinitions.isEmpty()) {
            return object;
        }
        String id = StoryboardPatchResolver.objectId(object);
        StoryboardObject registryObject = id != null ? registryDefinitions.get(id) : null;
        if (registryObject == null) {
            return object;
        }
        StoryboardObject merged = StoryboardPatchResolver.copyObject(registryObject);
        if (merged == null) {
            return object;
        }
        applyColorValidationPatch(merged, object);
        return merged;
    }

    private void applyColorValidationPatch(StoryboardObject target, StoryboardObject patch) {
        if (target == null || patch == null) {
            return;
        }
        String id = StoryboardPatchResolver.objectId(patch);
        if (id != null) {
            target.setId(id);
        }
        if (patch.getPlacement() != null && patch.getPlacement().hasData()) {
            StoryboardObject patchCopy = StoryboardPatchResolver.copyObject(patch);
            target.setPlacement(patchCopy != null ? patchCopy.getPlacement() : patch.getPlacement());
        }
        if (patch.getStyle() != null && patch.getStyle().hasData()) {
            StoryboardObject patchCopy = StoryboardPatchResolver.copyObject(patch);
            target.setStyle(patchCopy != null ? patchCopy.getStyle() : patch.getStyle());
        }
    }

    private List<ColorReference> collectColorReferences(StoryboardObject object) {
        List<ColorReference> colors = new ArrayList<>();
        if (object == null || object.getStyle() == null) {
            return colors;
        }
        StoryboardStyle style = object.getStyle();
        boolean isTextKind = isTextual(object);
        boolean isTextCard = containsAny(normalizeForSemanticCheck(object.getKind()), " text_card ", " formula_card ");
        boolean hasExplicitTextBackground = isTextKind && style.getFillOpacity() != null && style.getFillOpacity() > 0.0;
        collectColorValue("color", style.getColor(), isTextKind, false, colors);
        collectColorValue("fill_color", style.getFillColor(), false, isTextCard || hasExplicitTextBackground, colors);
        collectColorValue("stroke_color", style.getStrokeColor(), false, isTextCard, colors);
        collectColorValue("highlight_color", style.getHighlightColor(), false, false, colors);
        return colors;
    }

    private void collectColorValue(String propertyPath,
                                   String rawValue,
                                   boolean textLayer,
                                   boolean explicitBackground,
                                   List<ColorReference> colors) {
        if (rawValue == null) {
            return;
        }
        String color = rawValue.trim();
        if (color.isBlank()) {
            return;
        }
        colors.add(new ColorReference(propertyPath, color, textLayer, explicitBackground));
    }

    private void validateObjectColorContrast(String context,
                                             StoryboardObject object,
                                             List<ColorReference> colors,
                                             String outputTarget,
                                             List<String> issues) {
        List<ColorReference> validColors = new ArrayList<>();
        for (ColorReference color : colors) {
            if (isSixDigitHexColor(color.value)) {
                validColors.add(color);
            }
        }
        if (validColors.isEmpty()) {
            return;
        }
        String objectId = objectId(object);
        if (isTextualColorObject(object, validColors)) {
            ColorReference foreground = selectTextForeground(validColors);
            if (foreground == null) {
                return;
            }
            ColorReference background = selectTextBackground(validColors);
            String backgroundColor = background != null ? background.value : defaultStoryboardBackground(outputTarget);
            validateContrast(context, objectId, foreground.value, backgroundColor,
                    TEXT_CONTRAST_THRESHOLD, "text", issues);
            return;
        }
        for (ColorReference foreground : validColors) {
            if (foreground.explicitBackground) {
                continue;
            }
            validateContrast(context, objectId, foreground.value, defaultStoryboardBackground(outputTarget),
                    NON_TEXT_CONTRAST_THRESHOLD, "non-text", issues);
        }
    }

    private void validateContrast(String context,
                                  String objectId,
                                  String foreground,
                                  String background,
                                  double threshold,
                                  String category,
                                  List<String> issues) {
        double contrast = contrastRatio(foreground, background);
        if (contrast + 1e-9 < threshold) {
            issues.add(context + ": object '" + objectId + "' has insufficient " + category
                    + " color contrast; foreground=" + foreground.toUpperCase(Locale.ROOT)
                    + ", background=" + background.toUpperCase(Locale.ROOT)
                    + ", contrast=" + formatContrast(contrast)
                    + ", required>=" + formatContrast(threshold));
        }
    }

    private double contrastRatio(String foreground, String background) {
        double fg = relativeLuminance(foreground);
        double bg = relativeLuminance(background);
        double lighter = Math.max(fg, bg);
        double darker = Math.min(fg, bg);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private double relativeLuminance(String color) {
        int r = Integer.parseInt(color.substring(1, 3), 16);
        int g = Integer.parseInt(color.substring(3, 5), 16);
        int b = Integer.parseInt(color.substring(5, 7), 16);
        return 0.2126 * linearRgbChannel(r)
                + 0.7152 * linearRgbChannel(g)
                + 0.0722 * linearRgbChannel(b);
    }

    private double linearRgbChannel(int channel) {
        double srgb = channel / 255.0;
        return srgb <= 0.03928 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }

    private String formatContrast(double contrast) {
        return String.format(Locale.ROOT, "%.2f", contrast);
    }

    private boolean isSixDigitHexColor(String color) {
        return color != null && HEX_COLOR.matcher(color.trim()).matches();
    }

    private String defaultStoryboardBackground(String outputTarget) {
        return "geogebra".equalsIgnoreCase(outputTarget)
                ? DEFAULT_STORYBOARD_BACKGROUND_LIGHT
                : DEFAULT_STORYBOARD_BACKGROUND_DARK;
    }

    private boolean isTextual(StoryboardObject object) {
        if (object == null) {
            return false;
        }
        if (isTextRenderKind(normalizeForSemanticCheck(object.getKind()))) {
            return true;
        }
        if (object.getStyle() == null) {
            return false;
        }
        StoryboardStyle style = object.getStyle();
        return style.getFontSize() != null || StringUtils.hasText(style.getFontFamily());
    }

    private boolean isTextRenderKind(String kind) {
        return containsAny(kind,
                " text ", " label ", " text_card ", " equation ", " formula ", " formula_card ",
                " title ", " caption ");
    }

    private boolean isTextualColorObject(StoryboardObject object, List<ColorReference> colors) {
        if (isTextual(object)) {
            return true;
        }
        for (ColorReference color : colors) {
            if (color.textLayer) {
                return true;
            }
        }
        return false;
    }

    private ColorReference selectTextForeground(List<ColorReference> colors) {
        for (ColorReference color : colors) {
            if (color.textLayer && !color.explicitBackground) {
                return color;
            }
        }
        for (ColorReference color : colors) {
            if (!color.explicitBackground) {
                return color;
            }
        }
        return null;
    }

    private ColorReference selectTextBackground(List<ColorReference> colors) {
        for (ColorReference color : colors) {
            if ("fill_color".equals(color.propertyPath) && color.explicitBackground) {
                return color;
            }
        }
        return null;
    }

    private List<String> validateGeometricMarkerDefinitions(Storyboard storyboard) {
        return StoryboardGeometricMarkerValidator.validateStoryboard(storyboard);
    }

    private List<String> validateStructuredConstraints(Storyboard storyboard) {
        List<String> issues = new ArrayList<>();
        if (storyboard == null) {
            return issues;
        }
        Set<String> knownIds = new LinkedHashSet<>();
        Map<String, StoryboardObject> registryById = new LinkedHashMap<>();
        for (StoryboardObject object : safe(storyboard.getObjectRegistry())) {
            String id = StoryboardPatchResolver.objectId(object);
            if (id != null) {
                knownIds.add(id);
                registryById.put(id, object);
            }
        }

        for (StoryboardObject object : safe(storyboard.getObjectRegistry())) {
            String objectId = StoryboardPatchResolver.objectId(object);
            validateConstraintList("object_registry object '" + objectId + "'",
                    object != null ? object.getConstraints() : null,
                    knownIds, registryById, objectId,
                    StoryboardConstraintCatalog.Scope.OBJECT, issues);
        }

        List<StoryboardScene> scenes = new ArrayList<>(safe(storyboard.getScenes()));
        for (int i = 0; i < scenes.size(); i++) {
            StoryboardScene scene = scenes.get(i);
            String sceneId = scene != null ? scene.getSceneId() : null;
            validateConstraintList("scene " + (i + 1) + " (" + sceneId + ")",
                    scene != null ? scene.getConstraints() : null,
                    knownIds, registryById, null,
                    StoryboardConstraintCatalog.Scope.SCENE, issues);
        }
        return issues;
    }

    private void validateConstraintList(String scope,
                                        List<StoryboardConstraint> constraints,
                                        Set<String> knownIds,
                                        Map<String, StoryboardObject> registryById,
                                        String ownerId,
                                        StoryboardConstraintCatalog.Scope constraintScope,
                                        List<String> issues) {
        if (constraints == null || constraints.isEmpty()) {
            return;
        }
        Set<String> seenIds = new LinkedHashSet<>();
        for (int i = 0; i < constraints.size(); i++) {
            StoryboardConstraint constraint = constraints.get(i);
            String label = scope + " constraints[" + i + "]";
            if (constraint == null || !constraint.hasData()) {
                issues.add(label + ": empty constraint must be removed");
                continue;
            }
            StoryboardConstraintCatalog.RelationSpec relationSpec = null;
            if (!StringUtils.hasText(constraint.getDomain())) {
                issues.add(label + ": missing domain");
            } else if (!StoryboardConstraintCatalog.isValidDomain(constraint.getDomain())) {
                issues.add(label + ": unknown domain '" + constraint.getDomain().trim() + "'");
            }
            if (!StringUtils.hasText(constraint.getRelation())) {
                issues.add(label + ": missing relation");
            } else {
                relationSpec = StoryboardConstraintCatalog.relation(constraint.getDomain(), constraint.getRelation());
                if (relationSpec == null) {
                    issues.add(label + ": unknown relation '" + constraint.getRelation().trim()
                            + "'; use one of " + StoryboardConstraintCatalog.relationList());
                } else {
                    if (!relationSpec.allowsScope(constraintScope)) {
                        issues.add(label + ": relation '" + relationSpec.relation()
                                + "' is not valid for " + constraintScope.name().toLowerCase(Locale.ROOT)
                                + "-level constraints");
                    }
                    String domain = normalizeConstraintKey(constraint.getDomain());
                    if (!domain.isBlank() && !domain.equals(relationSpec.domain())) {
                        issues.add(label + ": domain '" + constraint.getDomain().trim()
                                + "' does not match relation '" + relationSpec.relation()
                                + "' domain '" + relationSpec.domain() + "'");
                    }
                }
            }
            if (StringUtils.hasText(constraint.getId()) && !seenIds.add(constraint.getId().trim())) {
                issues.add(label + ": duplicate constraint id '" + constraint.getId().trim() + "'");
            }
            if (!StringUtils.hasText(constraint.getStrength())) {
                issues.add(label + ": missing strength");
            } else if (!StoryboardConstraintCatalog.isValidStrength(constraint.getStrength())) {
                issues.add(label + ": strength must be hard, repair_hard, or soft");
            }
            Map<String, Object> refs = constraint.getRefs();
            if (refs == null || refs.isEmpty()) {
                issues.add(label + ": refs must map semantic roles to referenced object ids");
            } else {
                validateConstraintRefRoles(label, refs, relationSpec, issues);
                Set<String> referencedIds = new LinkedHashSet<>();
                collectConstraintRefs(label, refs, referencedIds, issues);
                for (String refId : referencedIds) {
                    if (!knownIds.contains(refId)) {
                        issues.add(label + ": refs references unknown id '" + refId + "'");
                    }
                }
                if (StringUtils.hasText(ownerId) && !referencedIds.contains(ownerId)) {
                    issues.add(label + ": object-level constraint should include its owner id '" + ownerId + "' in refs");
                }
                validateRelationKindCompatibility(label, constraint, registryById, issues);
            }
            validateConstraintParameters(label, constraint.getParameters(), relationSpec, knownIds, issues);
        }
    }

    private void validateRelationKindCompatibility(String label,
                                                   StoryboardConstraint constraint,
                                                   Map<String, StoryboardObject> registryById,
                                                   List<String> issues) {
        if (constraint == null || registryById == null || registryById.isEmpty()) {
            return;
        }
        String relation = normalizeConstraintKey(constraint.getRelation());
        switch (relation) {
            case "label_for":
                validateRefKind(label, constraint, registryById, "label",
                        List.of(" text ", " equation ", " formula ", " label ", " caption ", " title "), issues);
                break;
            case "moves_on_object":
                validateRefKind(label, constraint, registryById, "point", List.of(" point "), issues);
                break;
            case "connects_points":
                validateAnyRefKind(label, constraint, registryById,
                        List.of("object", "connector", "segment", "line", "ray"),
                        List.of(" segment ", " line ", " ray ", " vector "), issues);
                break;
            case "angle_between":
                validateRefKind(label, constraint, registryById, "marker",
                        List.of(" angle_marker ", " anglemarker ", " right_angle ", " rightangle ", " arc_marker "), issues);
                break;
            case "arc_sweep":
                validateAnyRefKind(label, constraint, registryById, List.of("marker", "arc"),
                        List.of(" arc ", " arc_marker ", " angle_marker ", " anglemarker "), issues);
                break;
            case "right_angle_at":
                validateRefKind(label, constraint, registryById, "marker",
                        List.of(" right_angle ", " rightangle ", " right_angle_marker ", " angle_marker ", " anglemarker "), issues);
                break;
            default:
                break;
        }
    }

    private void validateRefKind(String label,
                                 StoryboardConstraint constraint,
                                 Map<String, StoryboardObject> registryById,
                                 String role,
                                 List<String> allowedKindTokens,
                                 List<String> issues) {
        String id = resolveRefId(constraint.getRefs() != null ? constraint.getRefs().get(role) : null);
        if (id == null) {
            return;
        }
        StoryboardObject object = registryById.get(id);
        if (object == null) {
            return;
        }
        String kind = normalizeForSemanticCheck(object.getKind());
        if (!containsAny(kind, allowedKindTokens.toArray(new String[0]))) {
            issues.add(label + ": relation '" + constraint.getRelation() + "' refs." + role
                    + " must reference a compatible kind, but '" + id + "' has kind '" + object.getKind() + "'");
        }
    }

    private void validateAnyRefKind(String label,
                                    StoryboardConstraint constraint,
                                    Map<String, StoryboardObject> registryById,
                                    List<String> roles,
                                    List<String> allowedKindTokens,
                                    List<String> issues) {
        if (constraint.getRefs() == null) {
            return;
        }
        for (String role : roles) {
            String id = resolveRefId(constraint.getRefs().get(role));
            if (id == null) {
                continue;
            }
            StoryboardObject object = registryById.get(id);
            if (object == null) {
                return;
            }
            String kind = normalizeForSemanticCheck(object.getKind());
            if (!containsAny(kind, allowedKindTokens.toArray(new String[0]))) {
                issues.add(label + ": relation '" + constraint.getRelation() + "' refs." + role
                        + " must reference a compatible kind, but '" + id + "' has kind '" + object.getKind() + "'");
            }
            return;
        }
    }

    private void validateConstraintRefRoles(String label,
                                            Map<String, Object> refs,
                                            StoryboardConstraintCatalog.RelationSpec relationSpec,
                                            List<String> issues) {
        if (relationSpec == null || refs == null || refs.isEmpty()) {
            return;
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String role : refs.keySet()) {
            String normalizedRole = normalizeConstraintKey(role);
            if (normalizedRole.isBlank()) {
                issues.add(label + ": refs contains a blank role name");
                continue;
            }
            roles.add(normalizedRole);
            if (!relationSpec.allowedRefs().contains(normalizedRole)) {
                issues.add(label + ": relation '" + relationSpec.relation()
                        + "' does not allow refs role '" + role + "'");
            }
        }
        for (Set<String> requiredGroup : relationSpec.requiredRefGroups()) {
            boolean present = false;
            for (String role : requiredGroup) {
                if (roles.contains(role)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                issues.add(label + ": relation '" + relationSpec.relation()
                        + "' requires refs role " + describeRequiredRoleGroup(requiredGroup));
            }
        }
    }

    private void validateConstraintParameters(String label,
                                              Map<String, Object> parameters,
                                              StoryboardConstraintCatalog.RelationSpec relationSpec,
                                              Set<String> knownIds,
                                              List<String> issues) {
        if (relationSpec == null) {
            return;
        }
        Map<String, Object> safeParameters = parameters != null ? parameters : Map.of();
        Set<String> parameterKeys = new LinkedHashSet<>();
        for (String parameterName : safeParameters.keySet()) {
            String normalizedName = normalizeConstraintKey(parameterName);
            if (normalizedName.isBlank()) {
                issues.add(label + ": parameters contains a blank key");
                continue;
            }
            parameterKeys.add(normalizedName);
            if (!relationSpec.allowedParameters().contains(normalizedName)) {
                issues.add(label + ": relation '" + relationSpec.relation()
                        + "' does not allow parameter '" + parameterName + "'");
            }
        }
        for (String requiredParameter : relationSpec.requiredParameters()) {
            if (!parameterKeys.contains(requiredParameter)) {
                issues.add(label + ": relation '" + relationSpec.relation()
                        + "' requires parameter '" + requiredParameter + "'");
            }
        }
        for (Map.Entry<String, Object> entry : safeParameters.entrySet()) {
            String normalizedName = normalizeConstraintKey(entry.getKey());
            Set<String> enumValues = relationSpec.enumParameters().get(normalizedName);
            if (enumValues != null) {
                Object value = entry.getValue();
                if (!(value instanceof String)) {
                    issues.add(label + ": parameter '" + entry.getKey()
                            + "' must be one of " + String.join(", ", enumValues));
                } else {
                    String normalizedValue = normalizeConstraintKey((String) value);
                    if (!enumValues.contains(normalizedValue)) {
                        issues.add(label + ": parameter '" + entry.getKey()
                                + "' must be one of " + String.join(", ", enumValues));
                    }
                }
            }
            collectParameterObjectIds(label + " parameters." + entry.getKey(),
                    entry.getValue(), knownIds, issues);
        }
    }

    private void collectParameterObjectIds(String label,
                                           Object value,
                                           Set<String> knownIds,
                                           List<String> issues) {
        if (value == null || knownIds == null || knownIds.isEmpty()) {
            return;
        }
        if (value instanceof String) {
            String stringValue = ((String) value).trim();
            if (knownIds.contains(stringValue)) {
                issues.add(label + ": parameters must not contain object id '" + stringValue
                        + "'; put object references in refs");
            }
            return;
        }
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                collectParameterObjectIds(label, item, knownIds, issues);
            }
            return;
        }
        if (value instanceof Map<?, ?>) {
            for (Object nestedValue : ((Map<?, ?>) value).values()) {
                collectParameterObjectIds(label, nestedValue, knownIds, issues);
            }
        }
    }

    private String describeRequiredRoleGroup(Set<String> roles) {
        return roles.size() == 1 ? "'" + roles.iterator().next() + "'"
                : "one of [" + String.join(", ", roles) + "]";
    }

    private String normalizeConstraintKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void collectConstraintRefs(String label,
                                       Object value,
                                       Set<String> referencedIds,
                                       List<String> issues) {
        if (value == null) {
            issues.add(label + ": refs contains a null value");
            return;
        }
        if (value instanceof String) {
            String stringValue = (String) value;
            if (stringValue.isBlank()) {
                issues.add(label + ": refs contains a blank id");
            } else {
                referencedIds.add(stringValue.trim());
            }
            return;
        }
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                collectConstraintRefs(label, item, referencedIds, issues);
            }
            return;
        }
        if (value instanceof Map<?, ?>) {
            for (Object nestedValue : ((Map<?, ?>) value).values()) {
                collectConstraintRefs(label, nestedValue, referencedIds, issues);
            }
            return;
        }
        issues.add(label + ": refs values must be object ids or nested id lists/maps");
    }

    private String resolveRefId(Object refValue) {
        if (refValue instanceof String) {
            String s = ((String) refValue).trim();
            return s.isBlank() ? null : s;
        }
        return null;
    }

    private String normalizeForSemanticCheck(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_*']+", " ").trim() + " ";
    }

    private boolean containsAny(String haystack, String... needles) {
        if (!StringUtils.hasText(haystack)) {
            return false;
        }
        for (String needle : needles) {
            if (StringUtils.hasText(needle) && haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private void validateSceneLayout(String label,
                                     Storyboard storyboard,
                                     StoryboardScene layoutScene,
                                     List<String> issues) {
        if (layoutScene == null) {
            return;
        }
        List<StoryboardLayoutElement> elements = resolveSceneLayoutElements(label, layoutScene, issues);
        if (elements.isEmpty()) {
            return;
        }
        ValidationFrameBounds frameBounds = validationFrameBounds(storyboard);
        for (StoryboardLayoutElement element : elements) {
            String overflowSummary = summarizeOverflow(element.bounds, frameBounds);
            if (overflowSummary != null) {
                issues.add(formatOffscreenIssue(label, element, overflowSummary, elements));
            }
        }
        issues.addAll(evaluateLayoutOverlapIssues(label, elements));
    }

    private void expandCoordinateBoundsToFitPlacements(Storyboard storyboard,
                                                       List<StoryboardScene> layoutScenes,
                                                       String sceneMode) {
        if (storyboard == null || layoutScenes == null || layoutScenes.isEmpty()) {
            return;
        }
        Double minX = null;
        Double maxX = null;
        Double minY = null;
        Double maxY = null;
        double[] zExtents = new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        List<String> ignoredIssues = new ArrayList<>();
        for (StoryboardScene layoutScene : layoutScenes) {
            for (StoryboardLayoutElement element
                    : resolveSceneLayoutElements("coordinate-bounds-extent", layoutScene, ignoredIssues)) {
                StoryboardLayoutBounds bounds = element.bounds;
                minX = minX == null ? bounds.minX : Math.min(minX, bounds.minX);
                maxX = maxX == null ? bounds.maxX : Math.max(maxX, bounds.maxX);
                minY = minY == null ? bounds.minY : Math.min(minY, bounds.minY);
                maxY = maxY == null ? bounds.maxY : Math.max(maxY, bounds.maxY);
                if (SceneModeUtils.isThreeD(sceneMode)) {
                    collectZExtents(element.object, zExtents);
                }
            }
        }
        if (minX == null || minY == null) {
            return;
        }
        StoryboardCoordinateBounds current = CoordinateBoundsUtils.normalize(storyboard.getCoordinateBounds());
        double padding = CoordinateBoundsUtils.resolvePadding(current);
        StoryboardCoordinateBounds expanded = new StoryboardCoordinateBounds();
        expanded.setPadding(padding);
        expanded.setX(unionAxis(current != null ? current.getX() : null, minX - padding, maxX + padding));
        expanded.setY(unionAxis(current != null ? current.getY() : null, minY - padding, maxY + padding));
        if (SceneModeUtils.isThreeD(sceneMode)) {
            boolean hasZ = Double.isFinite(zExtents[0]) && Double.isFinite(zExtents[1]);
            expanded.setZ(hasZ
                    ? unionAxis(current != null ? current.getZ() : null,
                            zExtents[0] - padding, zExtents[1] + padding)
                    : current != null ? current.getZ() : null);
        }
        storyboard.setCoordinateBounds(CoordinateBoundsUtils.normalize(expanded));
    }

    private List<StoryboardLayoutElement> resolveSceneLayoutElements(String sceneLabel,
                                                                     StoryboardScene mergedScene,
                                                                     List<String> issues) {
        Map<String, StoryboardObject> visibleObjects = new LinkedHashMap<>();
        addVisibleObjects(visibleObjects, mergedScene.getPersistentObjects());
        addVisibleObjects(visibleObjects, mergedScene.getEnteringObjects());
        List<StoryboardLayoutElement> elements = new ArrayList<>();
        Map<String, StoryboardLayoutElement> cache = new LinkedHashMap<>();
        Set<String> resolvingIds = new HashSet<>();
        for (StoryboardObject object : visibleObjects.values()) {
            StoryboardLayoutElement element = resolveLayoutElement(
                    sceneLabel, object, visibleObjects, cache, resolvingIds, issues);
            if (element != null) {
                elements.add(element);
            }
        }
        return elements;
    }

    private void addVisibleObjects(Map<String, StoryboardObject> visibleObjects,
                                   List<StoryboardObject> objects) {
        for (StoryboardObject object : safe(objects)) {
            String id = StoryboardPatchResolver.objectId(object);
            if (id != null) {
                visibleObjects.put(id, object);
            }
        }
    }

    private StoryboardLayoutElement resolveLayoutElement(String sceneLabel,
                                                         StoryboardObject object,
                                                         Map<String, StoryboardObject> visibleObjects,
                                                         Map<String, StoryboardLayoutElement> cache,
                                                         Set<String> resolvingIds,
                                                         List<String> issues) {
        String id = StoryboardPatchResolver.objectId(object);
        if (id == null) {
            return null;
        }
        if (cache.containsKey(id)) {
            return cache.get(id);
        }
        if (object.getPlacement() == null || !object.getPlacement().hasData()) {
            cache.put(id, null);
            return null;
        }
        if (!resolvingIds.add(id)) {
            issues.add(sceneLabel + ": cyclic relative-placement dependency involving '" + id + "'");
            cache.put(id, null);
            return null;
        }
        try {
            StoryboardLayoutBounds bounds = resolveLayoutBounds(
                    sceneLabel, object, visibleObjects, cache, resolvingIds, issues);
            StoryboardLayoutElement element = bounds != null
                    ? new StoryboardLayoutElement(id, object, bounds)
                    : null;
            cache.put(id, element);
            return element;
        } finally {
            resolvingIds.remove(id);
        }
    }

    private StoryboardLayoutBounds resolveLayoutBounds(String sceneLabel,
                                                       StoryboardObject object,
                                                       Map<String, StoryboardObject> visibleObjects,
                                                       Map<String, StoryboardLayoutElement> cache,
                                                       Set<String> resolvingIds,
                                                       List<String> issues) {
        StoryboardPlacement placement = object.getPlacement();
        String positioning = StringUtils.hasText(placement.getPositioning())
                ? placement.getPositioning().trim()
                : StoryboardPlacement.POSITIONING_ABSOLUTE;
        AxisBounds xBounds;
        AxisBounds yBounds;
        if (StoryboardPlacement.POSITIONING_RELATIVE.equalsIgnoreCase(positioning)) {
            String anchorId = resolveAttachmentAnchorId(object);
            StoryboardObject anchor = visibleObjects.get(anchorId);
            if (anchor == null) {
                issues.add(sceneLabel + ": relative placement for '" + objectId(object)
                        + "' has no visible attachment anchor");
                return null;
            }
            StoryboardLayoutElement anchorElement = resolveLayoutElement(
                    sceneLabel, anchor, visibleObjects, cache, resolvingIds, issues);
            if (anchorElement == null) {
                return null;
            }
            xBounds = resolveAxisBounds(placement.getX(), anchorElement.bounds.centerX(), true);
            yBounds = resolveAxisBounds(placement.getY(), anchorElement.bounds.centerY(), true);
        } else if (StoryboardPlacement.POSITIONING_ABSOLUTE.equalsIgnoreCase(positioning)) {
            xBounds = resolveAxisBounds(placement.getX(), 0.0D, false);
            yBounds = resolveAxisBounds(placement.getY(), 0.0D, false);
        } else {
            issues.add(sceneLabel + ": object '" + objectId(object)
                    + "' has unsupported placement.positioning '" + positioning + "'");
            return null;
        }
        return inferObjectBounds(object, xBounds, yBounds);
    }

    private StoryboardLayoutBounds inferObjectBounds(StoryboardObject object,
                                                     AxisBounds xBounds,
                                                     AxisBounds yBounds) {
        double width = Math.max(xBounds.max - xBounds.min, 0.0D);
        double height = Math.max(yBounds.max - yBounds.min, 0.0D);
        if (width > 1e-9 && height > 1e-9) {
            return new StoryboardLayoutBounds(xBounds.min, xBounds.max, yBounds.min, yBounds.max);
        }
        boolean inferred = false;
        if (isTextLike(object)) {
            double fontSize = object.getStyle() != null && object.getStyle().getFontSize() != null
                    ? object.getStyle().getFontSize() : 24.0D;
            double unitHeight = Math.max(fontSize / 72.0D, 0.18D);
            width = Math.max(width, Math.max(visibleTextLength(object) * unitHeight * 0.33D, unitHeight));
            height = Math.max(height, unitHeight);
            inferred = true;
        } else if (isPointLike(object)) {
            double diameter = pointRadius(object) * 2.0D;
            width = Math.max(width, diameter);
            height = Math.max(height, diameter);
            inferred = true;
        }
        if (!inferred) {
            return new StoryboardLayoutBounds(xBounds.min, xBounds.max, yBounds.min, yBounds.max);
        }
        if (width <= 1e-9) {
            width = height;
        }
        if (height <= 1e-9) {
            height = width;
        }
        double centerX = (xBounds.min + xBounds.max) / 2.0D;
        double centerY = (yBounds.min + yBounds.max) / 2.0D;
        return new StoryboardLayoutBounds(
                round(centerX - width / 2.0D), round(centerX + width / 2.0D),
                round(centerY - height / 2.0D), round(centerY + height / 2.0D));
    }

    private int visibleTextLength(StoryboardObject object) {
        String text = object != null ? object.getContent() : null;
        return !StringUtils.hasText(text) ? 1 : Math.max(text.replaceAll("\\s+", "").length(), 1);
    }

    private boolean isPointLike(StoryboardObject object) {
        return object != null
                && containsAny(normalizeForSemanticCheck(object.getKind()), " point ", " dot ");
    }

    private double pointRadius(StoryboardObject object) {
        if (object != null && object.getStyle() != null) {
            StoryboardStyle style = object.getStyle();
            if (style.getRadius() != null && style.getRadius() > 0.0D) {
                return style.getRadius();
            }
            if (style.getPointSize() != null && style.getPointSize() > 0.0D) {
                return style.getPointSize() > 1.0D ? style.getPointSize() / 72.0D : style.getPointSize();
            }
        }
        return 0.12D;
    }

    private String resolveAttachmentAnchorId(StoryboardObject object) {
        if (object == null || object.getConstraints() == null) {
            return null;
        }
        String id = StoryboardPatchResolver.objectId(object);
        for (StoryboardConstraint constraint : object.getConstraints()) {
            if (!StoryboardConstraintCatalog.isAttachmentRelation(
                    constraint.getDomain(), constraint.getRelation()) || constraint.getRefs() == null) {
                continue;
            }
            String anchorId = resolveRefId(constraint.getRefs().get("anchor"));
            Set<String> ownerIds = StoryboardConstraintUtils.ownerIds(constraint);
            if (StringUtils.hasText(anchorId) && (ownerIds.isEmpty() || ownerIds.contains(id))) {
                return anchorId;
            }
        }
        return null;
    }

    private AxisBounds resolveAxisBounds(StoryboardPlacementAxis axis,
                                         double fallbackCenter,
                                         boolean relativeToBase) {
        if (axis == null || !axis.hasData()) {
            return new AxisBounds(fallbackCenter, fallbackCenter);
        }
        Double rawMin = axis.getMin() != null
                ? axis.getMin() : axis.getValue() != null ? axis.getValue() : axis.getMax();
        Double rawMax = axis.getMax() != null
                ? axis.getMax() : axis.getValue() != null ? axis.getValue() : axis.getMin();
        if (rawMin == null || rawMax == null) {
            return new AxisBounds(fallbackCenter, fallbackCenter);
        }
        double min = relativeToBase ? fallbackCenter + rawMin : rawMin;
        double max = relativeToBase ? fallbackCenter + rawMax : rawMax;
        return new AxisBounds(round(Math.min(min, max)), round(Math.max(min, max)));
    }

    private ValidationFrameBounds validationFrameBounds(Storyboard storyboard) {
        double[] min = CoordinateBoundsUtils.frameMin(storyboard,
                new double[] {FALLBACK_FRAME_MIN_X, FALLBACK_FRAME_MIN_Y, 0.0D});
        double[] max = CoordinateBoundsUtils.frameMax(storyboard,
                new double[] {FALLBACK_FRAME_MAX_X, FALLBACK_FRAME_MAX_Y, 0.0D});
        return new ValidationFrameBounds(min[0], max[0], min[1], max[1]);
    }

    private void collectZExtents(StoryboardObject object, double[] extents) {
        if (object == null || object.getPlacement() == null || object.getPlacement().getZ() == null
                || !object.getPlacement().getZ().hasData()) {
            return;
        }
        AxisBounds bounds = resolveAxisBounds(object.getPlacement().getZ(), 0.0D, false);
        extents[0] = Math.min(extents[0], bounds.min);
        extents[1] = Math.max(extents[1], bounds.max);
    }

    private StoryboardCoordinateBoundsAxis unionAxis(StoryboardCoordinateBoundsAxis current,
                                                     double requiredMin,
                                                     double requiredMax) {
        double min = current != null && current.getMin() != null
                ? Math.min(requiredMin, current.getMin()) : requiredMin;
        double max = current != null && current.getMax() != null
                ? Math.max(requiredMax, current.getMax()) : requiredMax;
        return new StoryboardCoordinateBoundsAxis(min, max);
    }

    private String summarizeOverflow(StoryboardLayoutBounds bounds, ValidationFrameBounds frame) {
        List<String> parts = new ArrayList<>();
        if (bounds.minX <= frame.minX || frame.minX - bounds.minX > OFFSCREEN_TOLERANCE) {
            parts.add("left=" + boundaryOverflow(frame.minX - bounds.minX));
        }
        if (bounds.maxX >= frame.maxX || bounds.maxX - frame.maxX > OFFSCREEN_TOLERANCE) {
            parts.add("right=" + boundaryOverflow(bounds.maxX - frame.maxX));
        }
        if (bounds.minY <= frame.minY || frame.minY - bounds.minY > OFFSCREEN_TOLERANCE) {
            parts.add("bottom=" + boundaryOverflow(frame.minY - bounds.minY));
        }
        if (bounds.maxY >= frame.maxY || bounds.maxY - frame.maxY > OFFSCREEN_TOLERANCE) {
            parts.add("top=" + boundaryOverflow(bounds.maxY - frame.maxY));
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private String boundaryOverflow(double value) {
        double rounded = round(Math.max(value, 0.0D));
        return rounded == 0.0D ? "0(boundary)" : Double.toString(rounded);
    }

    private String formatOffscreenIssue(String sceneLabel,
                                        StoryboardLayoutElement element,
                                        String overflowSummary,
                                        List<StoryboardLayoutElement> elements) {
        StringBuilder sb = new StringBuilder("Issue: ").append(sceneLabel)
                .append(": object '").append(element.objectId)
                .append("' extends outside the frame bounds (").append(overflowSummary).append(")");
        appendDependencyContext(sb, element, elements);
        return sb.toString();
    }

    private List<String> evaluateLayoutOverlapIssues(String sceneLabel,
                                                     List<StoryboardLayoutElement> elements) {
        List<String> issues = new ArrayList<>();
        Map<Long, List<Integer>> buckets = buildSpatialBuckets(elements);
        for (int index = 0; index < elements.size(); index++) {
            StoryboardLayoutElement left = elements.get(index);
            LayoutBucketRange range = bucketRange(left.bounds);
            Set<Integer> seen = new LinkedHashSet<>();
            for (int x = range.minX; x <= range.maxX; x++) {
                for (int y = range.minY; y <= range.maxY; y++) {
                    for (Integer candidate : buckets.getOrDefault(bucketKey(x, y), List.of())) {
                        if (candidate <= index || !seen.add(candidate)) {
                            continue;
                        }
                        StoryboardLayoutElement right = elements.get(candidate);
                        if (overlapsSignificantly(left.bounds, right.bounds)) {
                            String issue = classifyLayoutOverlap(sceneLabel, left, right, elements);
                            if (issue != null) {
                                issues.add(issue);
                            }
                        }
                    }
                }
            }
        }
        return issues;
    }

    private Map<Long, List<Integer>> buildSpatialBuckets(List<StoryboardLayoutElement> elements) {
        Map<Long, List<Integer>> buckets = new LinkedHashMap<>();
        for (int index = 0; index < elements.size(); index++) {
            LayoutBucketRange range = bucketRange(elements.get(index).bounds);
            for (int x = range.minX; x <= range.maxX; x++) {
                for (int y = range.minY; y <= range.maxY; y++) {
                    buckets.computeIfAbsent(bucketKey(x, y), ignored -> new ArrayList<>()).add(index);
                }
            }
        }
        return buckets;
    }

    private LayoutBucketRange bucketRange(StoryboardLayoutBounds bounds) {
        return new LayoutBucketRange(
                bucketIndex(bounds.minX), bucketIndex(bounds.maxX),
                bucketIndex(bounds.minY), bucketIndex(bounds.maxY));
    }

    private int bucketIndex(double value) {
        return (int) Math.floor(value / SPATIAL_BUCKET_SIZE);
    }

    private long bucketKey(int x, int y) {
        return (((long) x) << 32) ^ (y & 0xffffffffL);
    }

    private boolean overlapsSignificantly(StoryboardLayoutBounds left,
                                          StoryboardLayoutBounds right) {
        double width = Math.min(left.maxX, right.maxX) - Math.max(left.minX, right.minX);
        double height = Math.min(left.maxY, right.maxY) - Math.max(left.minY, right.minY);
        if (width <= 1e-9 || height <= 1e-9) {
            return false;
        }
        double area = width * height;
        double ratio = area / Math.min(Math.max(left.area(), 1e-9), Math.max(right.area(), 1e-9));
        return area >= MIN_OVERLAP_AREA && ratio >= MIN_OVERLAP_RATIO;
    }

    private String classifyLayoutOverlap(String sceneLabel,
                                         StoryboardLayoutElement left,
                                         StoryboardLayoutElement right,
                                         List<StoryboardLayoutElement> elements) {
        boolean leftText = isTextual(left.object);
        boolean rightText = isTextual(right.object);
        String issue;
        if (leftText && rightText) {
            issue = sceneLabel + ": text objects '" + left.objectId + "' and '" + right.objectId + "' overlap";
        } else if (leftText ^ rightText) {
            StoryboardLayoutElement text = leftText ? left : right;
            StoryboardLayoutElement other = leftText ? right : left;
            if (isAttachedLabelPair(text.object, other.object)) {
                return null;
            }
            issue = sceneLabel + ": text object '" + text.objectId
                    + "' overlaps object '" + other.objectId + "'";
        } else {
            issue = sceneLabel + ": objects '" + left.objectId + "' and '" + right.objectId + "' overlap";
        }
        StringBuilder sb = new StringBuilder(issue);
        appendDependencyContext(sb, left, elements);
        appendDependencyContext(sb, right, elements);
        return sb.toString();
    }

    private void appendDependencyContext(StringBuilder sb,
                                         StoryboardLayoutElement element,
                                         List<StoryboardLayoutElement> elements) {
        String context = formatDependencyContext(element.objectId, element.object, elements);
        if (StringUtils.hasText(context)) {
            sb.append("\n").append(context);
        }
    }

    private String formatDependencyContext(String objectId,
                                           StoryboardObject object,
                                           List<StoryboardLayoutElement> elements) {
        List<String> dependencies = constraintDependencyIds(object);
        if (dependencies.isEmpty()) {
            return "";
        }
        Map<String, StoryboardLayoutElement> byId = new LinkedHashMap<>();
        for (StoryboardLayoutElement element : elements) {
            byId.put(element.objectId, element);
        }
        StringBuilder sb = new StringBuilder("Dependency chain:\n- ")
                .append(objectId).append(" depends on [")
                .append(String.join(", ", dependencies)).append("]");
        String relations = formatConstraintRelationSummary(object);
        if (StringUtils.hasText(relations)) {
            sb.append(" via ").append(relations);
        }
        sb.append("\n");
        appendDependencyPlacementLines(dependencies, byId, new LinkedHashSet<>(), sb);
        return sb.toString();
    }

    private List<String> constraintDependencyIds(StoryboardObject object) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (object != null && object.getConstraints() != null) {
            for (StoryboardConstraint constraint : object.getConstraints()) {
                ids.addAll(StoryboardConstraintUtils.dependencyIds(constraint));
            }
            ids.remove(StoryboardPatchResolver.objectId(object));
        }
        return new ArrayList<>(ids);
    }

    private String formatConstraintRelationSummary(StoryboardObject object) {
        LinkedHashSet<String> relations = new LinkedHashSet<>();
        if (object != null && object.getConstraints() != null) {
            for (StoryboardConstraint constraint : object.getConstraints()) {
                if (constraint != null && StringUtils.hasText(constraint.getRelation())) {
                    relations.add(constraint.getRelation().trim());
                }
            }
        }
        return String.join(", ", relations);
    }

    private void appendDependencyPlacementLines(List<String> dependencyIds,
                                                Map<String, StoryboardLayoutElement> byId,
                                                Set<String> visited,
                                                StringBuilder sb) {
        for (String dependencyId : dependencyIds) {
            if (!StringUtils.hasText(dependencyId) || !visited.add(dependencyId)) {
                continue;
            }
            StoryboardLayoutElement dependency = byId.get(dependencyId);
            if (dependency == null) {
                sb.append("- ").append(dependencyId).append(": placement unavailable\n");
                continue;
            }
            sb.append("- ").append(dependencyId).append(": ")
                    .append(formatPlacementSummary(dependency.object)).append("\n");
            List<String> nested = constraintDependencyIds(dependency.object);
            if (!nested.isEmpty()) {
                sb.append("- ").append(dependencyId).append(" depends on [")
                        .append(String.join(", ", nested)).append("]\n");
                appendDependencyPlacementLines(nested, byId, visited, sb);
            }
        }
    }

    private String formatPlacementSummary(StoryboardObject object) {
        if (object == null || object.getPlacement() == null || !object.getPlacement().hasData()) {
            return "placement unavailable";
        }
        StoryboardPlacement placement = object.getPlacement();
        String positioning = StringUtils.hasText(placement.getPositioning())
                ? placement.getPositioning().trim() : StoryboardPlacement.POSITIONING_ABSOLUTE;
        List<String> axes = new ArrayList<>();
        String x = formatAxisSummary("x", placement.getX());
        String y = formatAxisSummary("y", placement.getY());
        if (StringUtils.hasText(x)) {
            axes.add(x);
        }
        if (StringUtils.hasText(y)) {
            axes.add(y);
        }
        return positioning + " placement"
                + (StoryboardPlacement.POSITIONING_RELATIVE.equalsIgnoreCase(positioning)
                    && StringUtils.hasText(resolveAttachmentAnchorId(object))
                    ? " anchor=" + resolveAttachmentAnchorId(object) : "")
                + (axes.isEmpty() ? "" : " " + String.join(", ", axes));
    }

    private String formatAxisSummary(String name, StoryboardPlacementAxis axis) {
        if (axis == null || !axis.hasData()) {
            return "";
        }
        if (axis.getValue() != null) {
            return name + "=" + round(axis.getValue());
        }
        if (axis.getMin() != null && axis.getMax() != null) {
            return name + "=" + round(axis.getMin()) + ".." + round(axis.getMax());
        }
        return axis.getMin() != null ? name + ">=" + round(axis.getMin())
                : name + "<=" + round(axis.getMax());
    }

    private boolean isAttachedLabelPair(StoryboardObject textObject, StoryboardObject otherObject) {
        String textId = StoryboardPatchResolver.objectId(textObject);
        String otherId = StoryboardPatchResolver.objectId(otherObject);
        if (textId == null || otherId == null || textObject.getConstraints() == null) {
            return false;
        }
        for (StoryboardConstraint constraint : textObject.getConstraints()) {
            if (!StoryboardConstraintCatalog.isAttachmentRelation(
                    constraint.getDomain(), constraint.getRelation()) || constraint.getRefs() == null) {
                continue;
            }
            Map<String, Object> refs = constraint.getRefs();
            boolean ownsText = textId.equals(resolveRefId(refs.get("label")))
                    || textId.equals(resolveRefId(refs.get("object")))
                    || textId.equals(resolveRefId(refs.get("attached")));
            if (ownsText && otherId.equals(resolveRefId(refs.get("anchor")))) {
                return true;
            }
        }
        return false;
    }

    private double round(double value) {
        return Math.round(value * 1_000_000.0D) / 1_000_000.0D;
    }

    private StoryboardCoordinateBounds defaultBounds(String sceneMode) {
        StoryboardCoordinateBounds bounds = new StoryboardCoordinateBounds();
        bounds.setPadding(StoryboardCoordinateBounds.DEFAULT_PADDING);
        bounds.setX(new StoryboardCoordinateBoundsAxis(-7.0D, 7.0D));
        bounds.setY(new StoryboardCoordinateBoundsAxis(-4.0D, 4.0D));
        if (SceneModeUtils.isThreeD(sceneMode)) {
            bounds.setZ(new StoryboardCoordinateBoundsAxis(-3.0D, 3.0D));
        }
        return bounds;
    }

    private List<StoryboardScene> buildValidationLayoutScenes(Storyboard storyboard) {
        List<StoryboardScene> layoutScenes = new ArrayList<>();
        Map<String, StoryboardObject> registry = registryById(storyboard);
        Map<String, StoryboardObject> visible = new LinkedHashMap<>();
        List<StoryboardObject> pendingExits = List.of();
        for (StoryboardScene scene : safe(storyboard.getScenes())) {
            removePatches(visible, pendingExits);
            Map<String, StoryboardObject> sceneVisible = copyObjectMap(visible);
            mergePatches(sceneVisible, registry, scene.getPersistentObjects());
            mergePatches(sceneVisible, registry, scene.getEnteringObjects());
            StoryboardScene layoutScene = new StoryboardScene();
            layoutScene.setSceneId(scene.getSceneId());
            layoutScene.setPersistentObjects(new ArrayList<>(sceneVisible.values()));
            layoutScene.setEnteringObjects(new ArrayList<>());
            layoutScene.setExitingObjects(new ArrayList<>());
            layoutScenes.add(layoutScene);
            visible = sceneVisible;
            pendingExits = safe(scene.getExitingObjects());
        }
        return layoutScenes;
    }

    private void mergePatches(Map<String, StoryboardObject> target,
                              Map<String, StoryboardObject> registry,
                              List<StoryboardObject> patches) {
        for (StoryboardObject patch : safe(patches)) {
            String id = objectId(patch);
            if (!StringUtils.hasText(id)) {
                continue;
            }
            StoryboardObject merged = copyObject(target.get(id));
            if (merged == null) {
                merged = copyObject(registry.get(id));
            }
            if (merged == null) {
                merged = new StoryboardObject();
                merged.setId(id);
            }
            if (patch.getPlacement() != null && patch.getPlacement().hasData()) {
                merged.setPlacement(copyObject(patch).getPlacement());
            }
            if (patch.getStyle() != null && patch.getStyle().hasData()) {
                merged.setStyle(copyObject(patch).getStyle());
            }
            target.put(id, merged);
        }
    }

    private void removePatches(Map<String, StoryboardObject> target, List<StoryboardObject> patches) {
        for (StoryboardObject patch : safe(patches)) {
            String id = objectId(patch);
            if (StringUtils.hasText(id)) {
                target.remove(id);
            }
        }
    }

    private StoryboardObject findVisiblePatch(StoryboardScene scene, String objectId) {
        StoryboardObject target = findPatch(scene.getEnteringObjects(), objectId);
        return target != null ? target : findPatch(scene.getPersistentObjects(), objectId);
    }

    private StoryboardObject findPatch(List<StoryboardObject> objects, String objectId) {
        for (StoryboardObject object : safe(objects)) {
            if (objectId.equals(objectId(object))) {
                return object;
            }
        }
        return null;
    }

    private Map<String, StoryboardScene> scenesById(Storyboard storyboard) {
        Map<String, StoryboardScene> map = new LinkedHashMap<>();
        for (StoryboardScene scene : safe(storyboard.getScenes())) {
            if (StringUtils.hasText(scene.getSceneId())) {
                map.put(scene.getSceneId().trim(), scene);
            }
        }
        return map;
    }

    private Map<String, StoryboardObject> registryById(Storyboard storyboard) {
        Map<String, StoryboardObject> map = new LinkedHashMap<>();
        for (StoryboardObject object : safe(storyboard.getObjectRegistry())) {
            String id = objectId(object);
            if (StringUtils.hasText(id)) {
                StoryboardObject copy = copyObject(object);
                copy.setPlacement(null);
                map.put(id, copy);
            }
        }
        return map;
    }

    private boolean hasVisibleObjectsNeedingPlacement(Storyboard storyboard) {
        for (StoryboardScene scene : buildValidationLayoutScenes(storyboard)) {
            for (StoryboardObject object : safe(scene.getPersistentObjects())) {
                if (object.getPlacement() == null || !object.getPlacement().hasData()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void preserveStepRefs(Storyboard source, Storyboard target) {
        if (source == null || target == null || source.getScenes() == null || target.getScenes() == null) {
            return;
        }
        int count = Math.min(source.getScenes().size(), target.getScenes().size());
        for (int i = 0; i < count; i++) {
            List<String> refs = source.getScenes().get(i).getStepRefs();
            if (refs != null && !refs.isEmpty()) {
                target.getScenes().get(i).setStepRefs(new ArrayList<>(refs));
            }
        }
    }

    private void normalizeStoryboard(Storyboard storyboard, String sceneMode) {
        if (storyboard == null) {
            return;
        }
        // Shared math-vision normalization first (scene field defaults, registry
        // placement nulling + constraint relocation, patch-mode + action defaults,
        // continuity/global-rule defaults). Then apply study-specific sceneMode
        // coordinate adaptation (default bounds + 2D z-axis stripping) on top.
        StoryboardNormalizer.normalize(storyboard);
        if (storyboard.getCoordinateBounds() == null) {
            storyboard.setCoordinateBounds(defaultBounds(sceneMode));
        }
        if (!SceneModeUtils.isThreeD(sceneMode) && storyboard.getCoordinateBounds() != null) {
            storyboard.getCoordinateBounds().setZ(null);
        }
        if (!SceneModeUtils.isThreeD(sceneMode)) {
            for (StoryboardScene scene : safe(storyboard.getScenes())) {
                stripPatchZAxis(scene.getEnteringObjects());
                stripPatchZAxis(scene.getPersistentObjects());
                stripPatchZAxis(scene.getExitingObjects());
            }
        }
    }

    private void stripPatchZAxis(List<StoryboardObject> patches) {
        for (StoryboardObject patch : safe(patches)) {
            if (patch.getPlacement() != null) {
                patch.getPlacement().setZ(null);
            }
        }
    }

    private StoryboardValidationReport baseReport(Storyboard storyboard, List<String> issues, String outputTarget) {
        StoryboardValidationReport report = new StoryboardValidationReport();
        report.setValidated(true);
        report.setPassed(issues == null || issues.isEmpty());
        report.setOutputTarget(outputTarget);
        report.setSceneCount(sceneCount(storyboard));
        report.setInitialIssues(new ArrayList<>(issues != null ? issues : List.of()));
        report.setInitialIssueCount(report.getInitialIssues().size());
        report.setFinalIssues(new ArrayList<>(report.getInitialIssues()));
        report.setFinalIssueCount(report.getFinalIssues().size());
        report.setResolvedIssueCount(0);
        return report;
    }

    private StoryboardValidationReport buildSkippedReport(String message, String outputTarget) {
        StoryboardValidationReport report = new StoryboardValidationReport();
        report.setValidated(false);
        report.setPassed(false);
        report.setOutputTarget(outputTarget);
        report.setMessage(message);
        return report;
    }

    private void finalizeReport(StoryboardValidationReport report,
                                boolean passed,
                                boolean fixAttempted,
                                boolean fixApplied,
                                List<String> finalIssues,
                                String message) {
        report.setPassed(passed);
        report.setFixAttempted(fixAttempted);
        report.setFixApplied(fixApplied);
        report.setFinalIssues(new ArrayList<>(finalIssues != null ? finalIssues : List.of()));
        report.setFinalIssueCount(report.getFinalIssues().size());
        report.setResolvedIssueCount(Math.max(0, report.getInitialIssueCount() - report.getFinalIssueCount()));
        report.setMessage(message);
    }

    private void appendTrace(StoryboardValidationReport report,
                             Storyboard storyboard,
                             String phase,
                             int cleanupAttempt,
                             boolean fixAttempted,
                             boolean fixApplied,
                             List<String> issues,
                             int toolCalls,
                             double seconds,
                             String message) {
        StoryboardValidationTraceEntry entry = new StoryboardValidationTraceEntry();
        entry.setSequence(report.getEntries().size() + 1);
        entry.setPhase(phase);
        entry.setCleanupAttempt(cleanupAttempt);
        entry.setPassed(issues == null || issues.isEmpty());
        entry.setSceneCount(sceneCount(storyboard));
        entry.setIssues(new ArrayList<>(issues != null ? issues : List.of()));
        entry.setIssueCount(entry.getIssues().size());
        entry.setFixAttempted(fixAttempted);
        entry.setFixApplied(fixApplied);
        entry.setToolCalls(toolCalls);
        entry.setExecutionTimeSeconds(seconds);
        entry.setMessage(message);
        report.addEntry(entry);
    }

    private String sceneCountMismatchIssue(int expected, int actual) {
        return "Storyboard cleanup changed the number of scenes from " + expected
                + " to " + actual + "; preserve one scene per graph teaching beat";
    }

    private String buildChainSummary(KnowledgeGraph graph, Storyboard storyboard) {
        String solutionChain = TargetDescriptionBuilder.buildSolutionChain(graph, null);
        if (StringUtils.hasText(solutionChain)) {
            return solutionChain;
        }
        if (storyboard == null || storyboard.getScenes() == null || storyboard.getScenes().isEmpty()) {
            return "DAG summary chain: unavailable.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("DAG summary chain:\n");
        int step = 1;
        for (StoryboardScene scene : storyboard.getScenes()) {
            if (scene == null) {
                continue;
            }
            sb.append(step++).append(". ");
            if (StringUtils.hasText(scene.getSceneId())) {
                sb.append(scene.getSceneId().trim()).append(" - ");
            }
            sb.append(StringUtils.hasText(scene.getTitle()) ? scene.getTitle().trim() : "Untitled scene");
            if (StringUtils.hasText(scene.getGoal())) {
                sb.append(" | goal: ").append(scene.getGoal().trim());
            }
            if (StringUtils.hasText(scene.getLayoutGoal())) {
                sb.append(" | layout: ").append(scene.getLayoutGoal().trim());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private Narrative copyNarrative(Narrative narrative) {
        return objectMapper.convertValue(narrative, Narrative.class);
    }

    private Storyboard copyStoryboard(Storyboard storyboard) {
        return objectMapper.convertValue(storyboard, Storyboard.class);
    }

    private StoryboardObject copyObject(StoryboardObject object) {
        return object != null ? objectMapper.convertValue(object, StoryboardObject.class) : null;
    }

    private Map<String, StoryboardObject> copyObjectMap(Map<String, StoryboardObject> source) {
        Map<String, StoryboardObject> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        for (Map.Entry<String, StoryboardObject> entry : source.entrySet()) {
            copy.put(entry.getKey(), copyObject(entry.getValue()));
        }
        return copy;
    }

    private String objectId(StoryboardObject object) {
        return object != null && StringUtils.hasText(object.getId()) ? object.getId().trim() : null;
    }

    private String textField(JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && value.isTextual() && StringUtils.hasText(value.asText())
                ? value.asText().trim()
                : null;
    }

    private int sceneCount(Storyboard storyboard) {
        return storyboard != null && storyboard.getScenes() != null ? storyboard.getScenes().size() : 0;
    }

    private <T> List<T> safe(List<T> list) {
        return list != null ? list : List.of();
    }

    private boolean isTextLike(StoryboardObject object) {
        String kind = object != null && object.getKind() != null ? object.getKind().toLowerCase(Locale.ROOT) : "";
        return kind.contains("text") || kind.contains("equation") || kind.contains("formula") || kind.contains("label");
    }

    private double secondsSince(Instant start) {
        return Duration.between(start, Instant.now()).toNanos() / 1_000_000_000.0D;
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    public static final class Result {
        private final Narrative narrative;
        private final StoryboardValidationReport report;
        private final int apiCalls;

        private Result(Narrative narrative, StoryboardValidationReport report, int apiCalls) {
            this.narrative = narrative;
            this.report = report;
            this.apiCalls = apiCalls;
        }

        public Narrative getNarrative() {
            return narrative;
        }

        public StoryboardValidationReport getReport() {
            return report;
        }

        public int getApiCalls() {
            return apiCalls;
        }
    }

    private static final class ColorReference {
        private final String propertyPath;
        private final String value;
        private final boolean textLayer;
        private final boolean explicitBackground;

        private ColorReference(String propertyPath, String value, boolean textLayer, boolean explicitBackground) {
            this.propertyPath = propertyPath == null ? "" : propertyPath;
            this.value = value == null ? "" : value.trim();
            this.textLayer = textLayer;
            this.explicitBackground = explicitBackground;
        }
    }

    private static final class Counter {
        private int value;

        private void increment() {
            value++;
        }
    }

    private static final class RollingTurn {
        private final String userPrompt;
        private final String assistantText;

        private RollingTurn(String userPrompt, String assistantText) {
            this.userPrompt = userPrompt;
            this.assistantText = assistantText;
        }
    }

    private static final class PlacementPatchMergeResult {
        private final Storyboard storyboard;
        private final List<String> issues;

        private PlacementPatchMergeResult(Storyboard storyboard, List<String> issues) {
            this.storyboard = storyboard;
            this.issues = issues != null ? issues : List.of();
        }

        private static PlacementPatchMergeResult accepted(Storyboard storyboard) {
            return new PlacementPatchMergeResult(storyboard, List.of());
        }

        private static PlacementPatchMergeResult rejected(List<String> issues) {
            return new PlacementPatchMergeResult(null, issues);
        }
    }

    private static final class StoryboardLayoutElement {
        private final String objectId;
        private final StoryboardObject object;
        private final StoryboardLayoutBounds bounds;

        private StoryboardLayoutElement(String objectId,
                                        StoryboardObject object,
                                        StoryboardLayoutBounds bounds) {
            this.objectId = objectId;
            this.object = object;
            this.bounds = bounds;
        }
    }

    private static final class StoryboardLayoutBounds {
        private final double minX;
        private final double maxX;
        private final double minY;
        private final double maxY;

        private StoryboardLayoutBounds(double minX, double maxX, double minY, double maxY) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }

        private double centerX() {
            return (minX + maxX) / 2.0D;
        }

        private double centerY() {
            return (minY + maxY) / 2.0D;
        }

        private double area() {
            return Math.max(maxX - minX, 0.0D) * Math.max(maxY - minY, 0.0D);
        }
    }

    private static final class ValidationFrameBounds {
        private final double minX;
        private final double maxX;
        private final double minY;
        private final double maxY;

        private ValidationFrameBounds(double minX, double maxX, double minY, double maxY) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }
    }

    private static final class AxisBounds {
        private final double min;
        private final double max;

        private AxisBounds(double min, double max) {
            this.min = min;
            this.max = max;
        }
    }

    private static final class LayoutBucketRange {
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;

        private LayoutBucketRange(int minX, int maxX, int minY, int maxY) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }
    }
}
