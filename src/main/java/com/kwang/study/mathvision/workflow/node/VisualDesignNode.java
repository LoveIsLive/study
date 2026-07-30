package com.kwang.study.mathvision.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardConstraint;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardCoordinateBoundsAxis;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardObject;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardPlacement;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardScene;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardStyle;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.VisualDesignRequest;
import com.kwang.study.mathvision.workflow.prompt.SystemPrompts;
import com.kwang.study.mathvision.workflow.prompt.ToolSchemas;
import com.kwang.study.mathvision.workflow.prompt.VisualDesignPrompts;
import com.kwang.study.mathvision.workflow.util.ProblemBundleContextBuilder;
import com.kwang.study.mathvision.workflow.util.StoryboardConstraintUtils;
import com.kwang.study.mathvision.workflow.util.StoryboardNormalizer;
import com.kwang.study.mathvision.workflow.util.StoryboardGeometricMarkerValidator;
import com.kwang.study.mathvision.workflow.util.TargetDescriptionBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class VisualDesignNode {

    private static final int DEFAULT_MAX_SCENE_RETRIES = 3;
    private static final Pattern NON_IDENTIFIER_CHARS = Pattern.compile("[^A-Za-z0-9_]");

    private final MathVisionAiChatService aiChatService;
    private final ObjectMapper objectMapper;
    private final MathVisionModelCatalog modelCatalog;

    public VisualDesignNode(MathVisionAiChatService aiChatService,
                            ObjectMapper objectMapper,
                            MathVisionModelCatalog modelCatalog) {
        this.aiChatService = aiChatService;
        this.objectMapper = objectMapper;
        this.modelCatalog = modelCatalog;
    }

    public Result run(MathVisionTask task,
                      ProblemBundle bundle,
                      KnowledgeGraph graph,
                      MathVisionStageExecutionContext context) {
        return run(task, bundle, graph, VisualDesignRequest.initialGeneration(), context);
    }

    /**
     * 显式视觉设计调用入口。只有 USER_REVISION 会读取并修改已有 Narrative；
     * 原有 run(...) 始终走 INITIAL_GENERATION，因此原工作流行为不变。
     */
    public Result run(MathVisionTask task,
                      ProblemBundle bundle,
                      KnowledgeGraph graph,
                      VisualDesignRequest request,
                      MathVisionStageExecutionContext context) {
        VisualDesignRequest resolvedRequest = request != null
                ? request
                : VisualDesignRequest.initialGeneration();
        bundle.setOutputTarget(task.getOutputTarget());
        if (graph == null || graph.countNodes() == 0) {
            throw new IllegalStateException("Missing reasoning_graph artifact. Run reasoning_graph first.");
        }
        validateRequest(resolvedRequest, graph);

        String sceneMode = normalizeSceneMode(bundle.getSceneMode());
        String outputTarget = task.getOutputTarget();
        String targetDescription = TargetDescriptionBuilder.build(bundle, graph, null);
        String solutionChain = TargetDescriptionBuilder.buildSolutionChain(graph, null);
        if (resolvedRequest.isUserRevision()) {
            return reviseCompleteStoryboard(
                    task,
                    bundle,
                    graph,
                    resolvedRequest,
                    context,
                    outputTarget,
                    sceneMode,
                    targetDescription,
                    solutionChain);
        }

        String rulesPrompt = VisualDesignPrompts.buildRulesPrompt(outputTarget, sceneMode);
        String fixedContext = VisualDesignPrompts.buildFixedContextPrompt(
                bundle, targetDescription, outputTarget, solutionChain, sceneMode);
        String schema = ToolSchemas.sceneDesign(outputTarget, sceneMode);

        DesignState state = new DesignState(sceneMode, outputTarget);
        List<RollingTurn> rollingTurns = new ArrayList<>();
        List<KnowledgeNode> teachingNodes = graph.teachingOrderNodes();
        int expectedScenes = teachingNodes.size();
        int apiCalls = 0;

        for (int i = 0; i < teachingNodes.size(); i++) {
            context.checkCanceled();
            KnowledgeNode node = teachingNodes.get(i);
            String userPrompt = buildScenePrompt(graph, node, i, expectedScenes, state);
            SceneDesignResult sceneResult = requestSceneWithRetry(
                    task, rulesPrompt, fixedContext, schema, rollingTurns, userPrompt, node, i, state);
            apiCalls += sceneResult.apiCalls;
            commitSceneResult(state, sceneResult);
            appendRollingTurn(rollingTurns, sceneResult.userPrompt, toPrettyJson(sceneResult.payload));
            context.checkCanceled();
        }

        Narrative narrative = assembleNarrative(bundle, graph, state);
        return new Result(narrative, apiCalls, expectedScenes, sceneMode);


    }

    private Result reviseCompleteStoryboard(MathVisionTask task,
                                            ProblemBundle bundle,
                                            KnowledgeGraph graph,
                                            VisualDesignRequest request,
                                            MathVisionStageExecutionContext context,
                                            String outputTarget,
                                            String sceneMode,
                                            String targetDescription,
                                            String solutionChain) {
        context.checkCanceled();
        Narrative baseline = request.getExistingNarrative();
        int expectedScenes = graph.teachingOrderNodes().size();
        String rulesPrompt = VisualDesignPrompts.buildRulesPrompt(outputTarget, sceneMode)
                + "\n\n" + buildRevisionRulesAppendix();
        String fixedContext = VisualDesignPrompts.buildFixedContextPrompt(
                bundle, targetDescription, outputTarget, solutionChain, sceneMode)
                + "\n\n" + buildRevisionFixedContextAppendix(request.getBaseStageVersion());
        String userPrompt = buildCompleteRevisionPrompt(baseline, request.getInstruction(), expectedScenes);

        JsonNode payload = aiChatService.requestJson(
                task,
                List.of(
                        AiMessage.system(rulesPrompt),
                        AiMessage.system(fixedContext),
                        AiMessage.user(List.of(AiContentPart.text(userPrompt)))
                ),
                ToolSchemas.storyboard(outputTarget, sceneMode));
        context.checkCanceled();

        Storyboard revisedStoryboard = parseCompleteRevisionStoryboard(payload, sceneMode);
        preserveRevisionSceneStructure(baseline.getStoryboard(), revisedStoryboard, expectedScenes);
        normalizeStoryboard(revisedStoryboard);
        Narrative revisedNarrative = new Narrative(baseline.getTargetDescription(), revisedStoryboard);
        List<String> issues = basicValidate(revisedNarrative, expectedScenes);
        if (!issues.isEmpty()) {
            throw new IllegalStateException(
                    "Complete storyboard revision returned an invalid artifact: " + String.join("; ", issues));
        }
        return new Result(revisedNarrative, 1, expectedScenes, sceneMode);
    }

    private String buildCompleteRevisionPrompt(Narrative baseline,
                                               String instruction,
                                               int expectedScenes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Revise the complete existing visual-storyboard artifact according to the user instruction.\n")
                .append("Treat the existing artifact as the authoritative revision baseline and make only the changes required by the instruction.\n")
                .append("Return one complete corrected storyboard through the full storyboard output contract; do not return a scene-level response, patch, diff, explanation, or partial fragment.\n")
                .append("Preserve unrelated scene content, scene order, scene ids, step_refs, canonical object ids, object lifecycle, mathematical meaning, and teaching intent.\n")
                .append("The revised storyboard must contain exactly ").append(expectedScenes)
                .append(" scenes, one for each reasoning-graph teaching node.\n\n")
                .append("User revision instruction:\n")
                .append(instruction.trim())
                .append("\n\nComplete existing Narrative artifact (revision baseline):\n```json\n")
                .append(toPrettyJson(baseline))
                .append("\n```\n\n")
                .append("Return the complete revised storyboard with coordinate_bounds, object_registry, all scenes, continuity_plan, and global_visual_rules.");
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }

    private Storyboard parseCompleteRevisionStoryboard(JsonNode payload, String sceneMode) {
        if (payload == null || payload.isNull()) {
            throw new IllegalStateException("AI did not return complete storyboard JSON");
        }
        JsonNode storyboardNode = payload.has("storyboard") ? payload.get("storyboard") : payload;
        try {
            Storyboard storyboard = objectMapper.treeToValue(
                    sanitizeLooseStoryboardFields(storyboardNode, sceneMode), Storyboard.class);
            if (storyboard == null) {
                throw new IllegalStateException("AI returned an empty storyboard");
            }
            return storyboard;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse complete revised storyboard: " + e.getMessage(), e);
        }
    }

    private void preserveRevisionSceneStructure(Storyboard baseline,
                                                Storyboard revised,
                                                int expectedScenes) {
        List<StoryboardScene> baselineScenes = baseline != null
                ? safeList(baseline.getScenes())
                : List.of();
        List<StoryboardScene> revisedScenes = revised != null
                ? safeList(revised.getScenes())
                : List.of();
        if (revisedScenes.size() != expectedScenes) {
            throw new IllegalStateException(
                    "Complete storyboard revision changed scene count: expected "
                            + expectedScenes + " but got " + revisedScenes.size());
        }
        for (int i = 0; i < revisedScenes.size(); i++) {
            StoryboardScene revisedScene = revisedScenes.get(i);
            if (revisedScene == null) {
                throw new IllegalStateException("Complete storyboard revision returned null scene at index " + i);
            }
            StoryboardScene baselineScene = i < baselineScenes.size() ? baselineScenes.get(i) : null;
            revisedScene.setSceneId(baselineScene != null && StringUtils.hasText(baselineScene.getSceneId())
                    ? baselineScene.getSceneId()
                    : "scene_" + (i + 1));
            if (baselineScene != null && baselineScene.getStepRefs() != null) {
                revisedScene.setStepRefs(new ArrayList<>(baselineScene.getStepRefs()));
            }
        }
    }

    private void validateRequest(VisualDesignRequest request, KnowledgeGraph graph) {
        if (!request.isUserRevision()) {
            return;
        }
        if (!StringUtils.hasText(request.getInstruction())) {
            throw new IllegalArgumentException("User revision instruction cannot be empty.");
        }
        if (request.getExistingNarrative() == null || !request.getExistingNarrative().hasStoryboard()) {
            throw new IllegalArgumentException("Existing storyboard is required for user revision.");
        }
        int existingScenes = safeList(
                request.getExistingNarrative().getStoryboard().getScenes()).size();
        int expectedScenes = graph.teachingOrderNodes().size();
        if (existingScenes != expectedScenes) {
            throw new IllegalArgumentException(
                    "Existing storyboard scene count does not match the reasoning graph: expected "
                            + expectedScenes + " but got " + existingScenes + ".");
        }
    }

    private SceneDesignResult requestSceneWithRetry(MathVisionTask task,
                                                    String rulesPrompt,
                                                    String fixedContext,
                                                    String schema,
                                                    List<RollingTurn> rollingTurns,
                                                    String baseUserPrompt,
                                                    KnowledgeNode node,
                                                    int index,
                                                    DesignState state) {
        RuntimeException lastError = null;
        String rejectionFeedback = null;
        int apiCalls = 0;
        int maxSceneRetries = maxSceneRetries();
        for (int attempt = 0; attempt <= maxSceneRetries; attempt++) {
            String userPrompt = baseUserPrompt;
            if (rejectionFeedback != null) {
                userPrompt += "\n\n" + rejectionFeedback;
            } else if (attempt > 0) {
                userPrompt += "\n\nPrevious attempt failed to produce a usable scene. Return the full scene and new_objects again.";
            }
            try {
                List<AiMessage> messages = new ArrayList<>();
                messages.add(AiMessage.system(rulesPrompt));
                messages.add(AiMessage.system(fixedContext));
                for (RollingTurn turn : rollingTurns) {
                    messages.add(AiMessage.user(List.of(AiContentPart.text(turn.userPrompt))));
                    messages.add(new AiMessage("assistant", List.of(AiContentPart.text(turn.assistantText))));
                }
                messages.add(AiMessage.user(List.of(AiContentPart.text(userPrompt))));
                JsonNode payload = aiChatService.requestJson(task, messages, schema);
                apiCalls++;
                SceneDesignResult parsed = parseSceneDesign(payload, node, index, state);
                parsed.apiCalls = apiCalls;
                parsed.payload = payload;
                parsed.userPrompt = userPrompt;

                if (parsed.scene != null) {
                    List<String> markerIssues = StoryboardGeometricMarkerValidator.validateSceneDesign(
                            parsed.scene,
                            parsed.newObjects,
                            new ArrayList<>(state.visibleObjectsSnapshot.values()),
                            new ArrayList<>(state.objectRegistry));
                    if (!markerIssues.isEmpty() && attempt < maxSceneRetries) {
                        rejectionFeedback = buildSceneRejectionRetryBlock(markerIssues, parsed);
                        continue;
                    }
                }
                return parsed;
            } catch (RuntimeException e) {
                lastError = e;
                rejectionFeedback = null;
            }
        }
        throw new IllegalStateException("Visual storyboard scene generation failed: " + node.getStep()
                + " - " + (lastError != null ? lastError.getMessage() : "unknown error"), lastError);
    }

    private int maxSceneRetries() {
        if (modelCatalog == null || modelCatalog.getWorkflow() == null
                || modelCatalog.getWorkflow().getVisualDesignSceneMaxRetries() == null) {
            return DEFAULT_MAX_SCENE_RETRIES;
        }
        return Math.max(modelCatalog.getWorkflow().getVisualDesignSceneMaxRetries(), 0);
    }

    private String buildSceneRejectionRetryBlock(List<String> issues, SceneDesignResult rejectedResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("Previous scene design was rejected by local geometric-marker validation.\n");
        sb.append("Regenerate the FULL `scene` and `new_objects` response for the same knowledge node; do not return a partial patch.\n");
        sb.append("Fix these issues exactly:\n");
        for (String issue : issues) {
            sb.append("- ").append(issue).append("\n");
        }
        sb.append("Geometric marker repair requirements:\n");
        sb.append("- `angle_marker` objects need `marker/angle_between` with marker, vertex, ordered start/end boundaries, and sector.\n");
        sb.append("- `arc` or `arc_marker` objects need `marker/arc_sweep` with marker/arc, center/anchor/vertex, start_boundary, end_boundary, direction, and sector.\n");
        sb.append("- `right_angle_marker` objects need `marker/right_angle_at` with marker, vertex, start_boundary, end_boundary, and side_of_reference.\n");
        sb.append("- Object refs must name existing registry ids or ids introduced in this response; never put object ids in parameters.\n");
        if (rejectedResult != null) {
            try {
                sb.append("Rejected response for reference:\n");
                Map<String, Object> reference = new LinkedHashMap<>();
                reference.put("scene", rejectedResult.scene);
                reference.put("new_objects", rejectedResult.newObjects);
                sb.append(toPrettyJson(reference));
            } catch (RuntimeException ignored) {
                // reference block is best-effort
            }
        }
        return sb.toString();
    }

    private SceneDesignResult parseSceneDesign(JsonNode payload,
                                               KnowledgeNode node,
                                               int index,
                                               DesignState state) {
        if (payload == null || payload.isNull()) {
            throw new IllegalStateException("AI did not return scene JSON");
        }
        JsonNode sceneNode = payload.has("scene") ? payload.get("scene") : payload;
        StoryboardScene scene;
        try {
            scene = objectMapper.treeToValue(sanitizeLooseStoryboardFields(sceneNode, state.sceneMode),
                    StoryboardScene.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse storyboard scene: " + e.getMessage(), e);
        }
        normalizeScene(scene, node, index);

        List<StoryboardObject> newObjects = parseCanonicalObjects(payload.get("new_objects"), state.sceneMode);

        StoryboardCoordinateBounds bounds = parseBoundsUpdate(payload, state.sceneMode);
        List<String> paletteColors = collectPaletteColors(scene);
        return new SceneDesignResult(scene, newObjects, bounds, paletteColors);
    }

    private List<StoryboardObject> parseCanonicalObjects(JsonNode objectsNode, String sceneMode) {
        List<StoryboardObject> objects = new ArrayList<>();
        if (objectsNode == null || !objectsNode.isArray()) {
            return objects;
        }
        for (JsonNode objectNode : objectsNode) {
            try {
                StoryboardObject object = objectMapper.treeToValue(
                        sanitizeLooseStoryboardFields(objectNode, sceneMode), StoryboardObject.class);
                if (object != null && StringUtils.hasText(object.getId())) {
                    object.setId(sanitizeIdentifier(object.getId(), "obj"));
                    object.setPlacement(null);
                    normalizeListFields(object);
                    objects.add(object);
                }
            } catch (Exception ignored) {
                // Keep the existing tolerant object parsing behavior.
            }
        }
        return objects;
    }

    private void commitSceneResult(DesignState state, SceneDesignResult result) {
        Map<String, StoryboardObject> registryById = new LinkedHashMap<>(state.registryById);
        for (StoryboardObject object : result.newObjects) {
            String id = objectId(object);
            if (!StringUtils.hasText(id) || registryById.containsKey(id)) {
                continue;
            }
            StoryboardObject registryObject = copyObject(object);
            registryObject.setPlacement(null);
            state.objectRegistry.add(registryObject);
            state.registryById.put(id, registryObject);
            registryById.put(id, registryObject);
        }

        state.boundsAccumulator.merge(result.coordinateBoundsUpdate);
        if (result.scene != null) {
            Map<String, StoryboardObject> registryDefinitions = new LinkedHashMap<>(state.registryById);
            stripCoordinateDerivedPlacements(result.scene, result.newObjects, registryDefinitions,
                    state.visibleObjectsSnapshot);
            if ("geogebra".equalsIgnoreCase(state.outputTarget)) {
                stripGeoGebraDefaultPlaceablePlacements(result.scene, result.newObjects, registryDefinitions,
                        state.visibleObjectsSnapshot);
            }
        }
        state.scenes.add(result.scene);
        state.visibleObjects.clear();
        state.visibleObjects.putAll(computeVisibleState(state.visibleObjectsSnapshot, registryById, result.scene));
        state.visibleObjectsSnapshot = copyObjectMap(state.visibleObjects);
        state.palette.addAll(result.paletteColors);
    }

    private Map<String, StoryboardObject> computeVisibleState(Map<String, StoryboardObject> previous,
                                                              Map<String, StoryboardObject> registryById,
                                                              StoryboardScene scene) {
        Map<String, StoryboardObject> next = copyObjectMap(previous);
        mergePatches(next, registryById, scene.getPersistentObjects());
        mergePatches(next, registryById, scene.getEnteringObjects());
        removePatches(next, scene.getExitingObjects());
        return next;
    }

    private void mergePatches(Map<String, StoryboardObject> target,
                              Map<String, StoryboardObject> registryById,
                              List<StoryboardObject> patches) {
        if (patches == null) {
            return;
        }
        for (StoryboardObject patch : patches) {
            String id = objectId(patch);
            if (!StringUtils.hasText(id)) {
                continue;
            }
            StoryboardObject merged = copyObject(target.get(id));
            if (merged == null) {
                merged = copyObject(registryById.get(id));
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

    private void stripCoordinateDerivedPlacements(StoryboardScene scene,
                                                  List<StoryboardObject> newObjects,
                                                  Map<String, StoryboardObject> registryDefinitions,
                                                  Map<String, StoryboardObject> visibleSnapshot) {
        Set<String> ownerIds = collectConstraintOwnerIds(scene, newObjects, registryDefinitions, visibleSnapshot, true);
        if (ownerIds.isEmpty()) {
            return;
        }
        stripPlacementFromPatches(scene.getEnteringObjects(), ownerIds);
        stripPlacementFromPatches(scene.getPersistentObjects(), ownerIds);
    }

    private void stripGeoGebraDefaultPlaceablePlacements(StoryboardScene scene,
                                                         List<StoryboardObject> newObjects,
                                                         Map<String, StoryboardObject> registryDefinitions,
                                                         Map<String, StoryboardObject> visibleSnapshot) {
        Set<String> ownerIds = collectConstraintOwnerIds(scene, newObjects, registryDefinitions, visibleSnapshot, false);
        if (ownerIds.isEmpty()) {
            return;
        }
        stripPlacementFromPatches(scene.getEnteringObjects(), ownerIds);
        stripPlacementFromPatches(scene.getPersistentObjects(), ownerIds);
    }

    private Set<String> collectConstraintOwnerIds(StoryboardScene scene,
                                                  List<StoryboardObject> newObjects,
                                                  Map<String, StoryboardObject> registryDefinitions,
                                                  Map<String, StoryboardObject> visibleSnapshot,
                                                  boolean coordinateDerived) {
        Set<String> ownerIds = new LinkedHashSet<>();
        if (scene != null) {
            addOwnerIds(ownerIds, scene.getConstraints(), coordinateDerived);
            addOwnerIds(ownerIds, objectConstraints(scene.getEnteringObjects()), coordinateDerived);
            addOwnerIds(ownerIds, objectConstraints(scene.getPersistentObjects()), coordinateDerived);
        }
        addOwnerIds(ownerIds, objectConstraints(newObjects), coordinateDerived);
        if (visibleSnapshot != null) {
            addOwnerIds(ownerIds, objectConstraints(new ArrayList<>(visibleSnapshot.values())), coordinateDerived);
        }
        if (registryDefinitions != null) {
            addOwnerIds(ownerIds, objectConstraints(new ArrayList<>(registryDefinitions.values())), coordinateDerived);
        }
        return ownerIds;
    }

    private List<StoryboardConstraint> objectConstraints(List<StoryboardObject> objects) {
        List<StoryboardConstraint> constraints = new ArrayList<>();
        if (objects == null) {
            return constraints;
        }
        for (StoryboardObject object : objects) {
            if (object != null && object.getConstraints() != null) {
                constraints.addAll(object.getConstraints());
            }
        }
        return constraints;
    }

    private void addOwnerIds(Set<String> ownerIds, List<StoryboardConstraint> constraints, boolean coordinateDerived) {
        if (constraints == null) {
            return;
        }
        for (StoryboardConstraint constraint : constraints) {
            boolean matches = coordinateDerived
                    ? StoryboardConstraintUtils.isCoordinateDerivedConstraint(constraint)
                    : StoryboardConstraintUtils.isGeoGebraDefaultPlaceableConstraint(constraint);
            if (matches) {
                ownerIds.addAll(StoryboardConstraintUtils.ownerIds(constraint));
            }
        }
    }

    private void stripPlacementFromPatches(List<StoryboardObject> patches, Set<String> ownerIds) {
        if (patches == null || ownerIds.isEmpty()) {
            return;
        }
        for (StoryboardObject patch : patches) {
            String id = objectId(patch);
            if (id != null && ownerIds.contains(id)) {
                patch.setPlacement(null);
            }
        }
    }

    private void removePatches(Map<String, StoryboardObject> target, List<StoryboardObject> patches) {
        if (patches == null) {
            return;
        }
        for (StoryboardObject patch : patches) {
            String id = objectId(patch);
            if (StringUtils.hasText(id)) {
                target.remove(id);
            }
        }
    }

    private Narrative assembleNarrative(ProblemBundle bundle, KnowledgeGraph graph, DesignState state) {
        List<StoryboardScene> scenes = new ArrayList<>(state.scenes);
        scenes.sort(Comparator.comparingInt(scene -> sceneNumber(scene.getSceneId())));
        for (int i = 0; i < scenes.size(); i++) {
            scenes.get(i).setSceneId("scene_" + (i + 1));
        }

        Storyboard storyboard = new Storyboard();
        storyboard.setScenes(scenes);
        storyboard.setObjectRegistry(new ArrayList<>(state.objectRegistry));
        storyboard.setCoordinateBounds(state.boundsAccumulator.toStoryboardBounds());
        storyboard.setContinuityPlan("Objects maintain stable ids across scenes via the global object registry.");

        List<String> globalRules = new ArrayList<>();
        globalRules.add("Keep resolved storyboard placements strictly inside coordinate_bounds.");
        globalRules.add("Reuse stable anchors for persistent objects.");
        if (!state.palette.isEmpty()) {
            globalRules.add("Color palette: " + String.join(", ", state.palette));
        }
        storyboard.setGlobalVisualRules(globalRules);
        normalizeStoryboard(storyboard);

        KnowledgeNode terminal = graph.findPrimaryTerminalNode();
        String targetDescription = ProblemBundleContextBuilder.workflowTargetDescription(
                bundle,
                terminal != null ? terminal.getStep() : "",
                "",
                state.outputTarget);
        return new Narrative(targetDescription, storyboard);
    }

    private List<String> basicValidate(Narrative narrative, int expectedScenes) {
        List<String> issues = new ArrayList<>();
        if (narrative == null || narrative.getStoryboard() == null) {
            issues.add("Storyboard is missing");
            return issues;
        }
        Storyboard storyboard = narrative.getStoryboard();
        if (storyboard.getScenes() == null || storyboard.getScenes().isEmpty()) {
            issues.add("Storyboard has no scenes");
            return issues;
        }
        if (storyboard.getScenes().size() != expectedScenes) {
            issues.add("Scene count mismatch: expected " + expectedScenes + " but got " + storyboard.getScenes().size());
        }
        Set<String> registryIds = new LinkedHashSet<>();
        for (StoryboardObject object : safeList(storyboard.getObjectRegistry())) {
            String id = objectId(object);
            if (!StringUtils.hasText(id)) {
                issues.add("Object registry contains object without id");
                continue;
            }
            if (!registryIds.add(id)) {
                issues.add("Duplicate object id in registry: " + id);
            }
        }
        for (StoryboardScene scene : storyboard.getScenes()) {
            String label = StringUtils.hasText(scene.getSceneId()) ? scene.getSceneId() : "unknown scene";
            validatePatchRefs(label, "entering_objects", scene.getEnteringObjects(), registryIds, issues);
            validatePatchRefs(label, "persistent_objects", scene.getPersistentObjects(), registryIds, issues);
            validatePatchRefs(label, "exiting_objects", scene.getExitingObjects(), registryIds, issues);
            for (StoryboardAction action : safeList(scene.getActions())) {
                for (String target : safeList(action.getTargets())) {
                    if (StringUtils.hasText(target) && !registryIds.contains(target)) {
                        issues.add(label + " action target not in registry: " + target);
                    }
                }
            }
        }
        return issues;
    }

    private void validatePatchRefs(String sceneLabel,
                                   String field,
                                   List<StoryboardObject> patches,
                                   Set<String> registryIds,
                                   List<String> issues) {
        for (StoryboardObject patch : safeList(patches)) {
            String id = objectId(patch);
            if (!StringUtils.hasText(id)) {
                issues.add(sceneLabel + " " + field + " contains patch without id");
            } else if (!registryIds.contains(id)) {
                issues.add(sceneLabel + " " + field + " references object not in registry: " + id);
            }
        }
    }

    private String buildScenePrompt(KnowledgeGraph graph,
                                    KnowledgeNode node,
                                    int index,
                                    int expectedScenes,
                                    DesignState state) {
        return SystemPrompts.buildCurrentRequestSection(
                buildScenePromptBody(graph, node, index, expectedScenes, state));
    }

    private String buildScenePromptBody(KnowledgeGraph graph,
                                        KnowledgeNode node,
                                        int index,
                                        int expectedScenes,
                                        DesignState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("Design a scene for this knowledge node:\n");
        sb.append("- Step: ").append(node.getStep()).append("\n");
        sb.append("- Node type: ").append(node.getNodeType()).append("\n");
        sb.append("- Depth: ").append(node.getMinDepth()).append("\n");
        if (state.objectRegistry.isEmpty()) {
            sb.append("- This is the first scene. All objects must be in entering_objects; persistent_objects and exiting_objects must be empty.\n");
        }
        if (StringUtils.hasText(node.getReason())) {
            sb.append("- Reason: ").append(node.getReason()).append("\n");
        }

        List<KnowledgeNode> prerequisites = graph.getPrerequisites(node.getId());
        List<KnowledgeNode> dependents = graph.getDependents(node.getId());
        if (!prerequisites.isEmpty() || !dependents.isEmpty()) {
            sb.append("Graph-neighbor context (navigation only, not mathematical authority):\n");
            if (!prerequisites.isEmpty()) {
                sb.append("- Direct prerequisite scene count: ").append(prerequisites.size())
                        .append("; use conversation history and the object registry for continuity instead of copying neighbor step text into this request.\n");
            }
            if (!dependents.isEmpty()) {
                sb.append("- Direct downstream scene count: ").append(dependents.size())
                        .append("; use this only to avoid premature exits or reveals, not to introduce downstream claims early.\n");
            }
        }
        if (prerequisites.size() > 1) {
            sb.append("Merge scene guidance:\n");
            sb.append("- This scene merges multiple prerequisite branches.\n");
            sb.append("- Reuse established object ids, color meanings, and continuity anchors.\n");
            sb.append("- Integrate the upstream conclusions in one scene instead of replaying each branch.\n");
            sb.append("- If branch context conflicts with the ProblemBundle, keep the ProblemBundle-consistent meaning.\n");
        }

        appendEnrichmentContext(sb, node);
        sb.append("\nGlobal visual context:\n");
        sb.append("- Input mode: ").append(graph.isProblemMode() ? "problem" : "concept").append("\n\n");
        sb.append(buildGlobalObjectRegistrySummary(state));
        sb.append("\n\n");
        sb.append(buildVisibleObjectRegistrySummary(state));
        if (!state.palette.isEmpty()) {
            sb.append("\nColors already used: ").append(String.join(", ", state.palette)).append(".");
        } else {
            sb.append("\nNo colors have been assigned yet.");
        }
        return sb.toString();
    }

    private String buildRevisionRulesAppendix() {
        return SystemPrompts.buildRulesSection(
                "Complete storyboard user-revision mode. This section overrides only the scene-level response granularity in the visual-design rules above:\n"
                        + "- Keep all original visual-design, mathematical, layout, object-lifecycle, and output-target rules in force.\n"
                        + "- Treat the supplied complete Narrative and storyboard as the authoritative revision baseline.\n"
                        + "- Apply only changes required by the user instruction and preserve unrelated teaching content.\n"
                        + "- Preserve scene ids, stable object ids, mathematical meaning, teaching order, and object lifecycle unless the instruction explicitly requires a change.\n"
                        + "- Preserve one scene per reasoning-graph teaching node and keep existing step_refs bound to the same scene positions.\n"
                        + "- For this call, do not return the original per-scene {scene, new_objects} response. Consolidate canonical object definitions in object_registry instead.\n"
                        + "- Return one complete revised storyboard, not a scene-level response, patch, diff, explanation, or partial fragment.\n"
                        + "- Reuse baseline object ids wherever their semantic role is preserved; do not recreate unchanged objects under new ids.");
    }

    private String buildRevisionFixedContextAppendix(Integer baseStageVersion) {
        StringBuilder sb = new StringBuilder("Operation mode: user_revision.\n");
        if (baseStageVersion != null) {
            sb.append("Base visual_storyboard stage version: ").append(baseStageVersion).append(".\n");
        }
        sb.append("The existing storyboard is the revision baseline. User feedback may change presentation, but it must not contradict the ProblemBundle or reasoning graph.\n");
        sb.append("The current request contains the complete existing Narrative artifact and the user revision instruction.\n");
        sb.append("Perform the revision in one model call and return the complete storyboard through the full-storyboard schema.\n");
        return SystemPrompts.buildFixedContextSection(sb.toString());
    }

    private void appendEnrichmentContext(StringBuilder sb, KnowledgeNode node) {
        boolean hasEquations = node.getEquations() != null && !node.getEquations().isEmpty();
        boolean hasDefinitions = node.getDefinitions() != null && !node.getDefinitions().isEmpty();
        boolean hasInterpretation = StringUtils.hasText(node.getInterpretation());
        boolean hasExamples = node.getExamples() != null && !node.getExamples().isEmpty();
        if (!hasEquations && !hasDefinitions && !hasInterpretation && !hasExamples) {
            return;
        }
        sb.append("\nMathematical enrichment for this node (Stage 2 supplemental fields):\n");
        if (hasEquations) {
            sb.append("Equations:\n");
            for (String equation : node.getEquations()) {
                sb.append("- ").append(equation).append("\n");
            }
        }
        if (hasDefinitions) {
            sb.append("Definitions:\n");
            node.getDefinitions().forEach((symbol, definition) ->
                    sb.append("- ").append(symbol).append(": ").append(definition).append("\n"));
        }
        if (hasInterpretation) {
            sb.append("Interpretation: ").append(node.getInterpretation()).append("\n");
        }
        if (hasExamples) {
            sb.append("Examples:\n");
            for (String example : node.getExamples()) {
                sb.append("- ").append(example).append("\n");
            }
        }
    }

    private String buildGlobalObjectRegistrySummary(DesignState state) {
        if (state.objectRegistry.isEmpty()) {
            return "Global object registry: empty. Since no object has been defined yet, every visible object introduced in this first scene needs a `new_objects` definition.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Global object registry - all ids already defined in earlier scenes, including objects that may no longer be visible (")
                .append(state.objectRegistry.size()).append(" objects):\n");
        for (StoryboardObject object : state.objectRegistry) {
            appendRegistryObjectDefinitionSummary(sb, object);
        }
        sb.append("Do not repeat these ids in `new_objects`. If one of these objects re-enters the scene, reference its existing id in `entering_objects` with any needed placement/style patch.");
        return sb.toString();
    }

    private String buildVisibleObjectRegistrySummary(DesignState state) {
        if (state.visibleObjects.isEmpty()) {
            return "Currently visible object registry: empty (no objects are currently visible). Previously defined global objects may still re-enter via `entering_objects`, but they must not be repeated in `new_objects`.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Currently visible object registry - objects visible immediately before this scene, with current visual state (")
                .append(state.visibleObjects.size()).append(" objects):\n");
        for (StoryboardObject object : state.visibleObjects.values()) {
            int lineStart = sb.length();
            appendRegistryObjectDefinitionSummary(sb, object);
            int insertAt = sb.length() - 1;
            StringBuilder visualState = new StringBuilder();
            if (object.getPlacement() != null && object.getPlacement().hasData()) {
                visualState.append(", placement=").append(formatPlacement(object.getPlacement()));
            }
            if (object.getStyle() != null && object.getStyle().hasData()) {
                visualState.append(", style=").append(formatStyle(object.getStyle()));
            }
            if (visualState.length() > 0 && insertAt >= lineStart) {
                sb.insert(insertAt, visualState);
            }
        }
        sb.append("Refer to these currently visible ids in `persistent_objects` and `exiting_objects`; use `entering_objects` for new or re-entering visible objects.");
        return sb.toString();
    }

    private void appendRegistryObjectDefinitionSummary(StringBuilder sb, StoryboardObject object) {
        sb.append("- id=").append(object.getId())
                .append(", kind=").append(object.getKind())
                .append(", content=").append(object.getContent() == null ? "" : object.getContent());
        if (object.getConstraints() != null && !object.getConstraints().isEmpty()) {
            sb.append(", constraints=").append(toJson(object.getConstraints()));
        }
        sb.append("\n");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private StoryboardCoordinateBounds parseBoundsUpdate(JsonNode payload, String sceneMode) {
        JsonNode node = payload != null ? payload.get("coordinate_bounds_update") : null;
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            StoryboardCoordinateBounds bounds = objectMapper.treeToValue(
                    sanitizeLooseStoryboardFields(node, sceneMode), StoryboardCoordinateBounds.class);
            return normalizeBounds(bounds, sceneMode);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode sanitizeLooseStoryboardFields(JsonNode node, String sceneMode) {
        if (node == null || node.isNull()) {
            return node;
        }
        JsonNode copy = node.deepCopy();
        sanitizeLooseStoryboardFieldsInPlace(copy, sceneMode);
        return copy;
    }

    private void sanitizeLooseStoryboardFieldsInPlace(JsonNode node, String sceneMode) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                sanitizeLooseStoryboardFieldsInPlace(item, sceneMode);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        ObjectNode objectNode = (ObjectNode) node;
        if (objectNode.has("zindex") && !objectNode.has("z_index")) {
            objectNode.set("z_index", objectNode.get("zindex"));
            objectNode.remove("zindex");
        }
        if (!"3d".equals(sceneMode)) {
            objectNode.remove("z");
        }
        JsonNode placement = objectNode.get("placement");
        if (placement != null && placement.isValueNode() && !placement.isNull()) {
            objectNode.remove("placement");
        }
        objectNode.fields().forEachRemaining(entry ->
                sanitizeLooseStoryboardFieldsInPlace(entry.getValue(), sceneMode));
    }

    private void normalizeScene(StoryboardScene scene, KnowledgeNode node, int index) {
        // Match math-vision VisualDesignNode.parseSceneDesign: set node-derived
        // scene_id + step_refs, then delegate all remaining field defaults and
        // patch/action normalization to the shared StoryboardNormalizer.
        scene.setSceneId("scene_" + (index + 1));
        scene.setStepRefs(List.of(node.getStep()));
        StoryboardNormalizer.normalizeScene(scene, index);
    }

    private void normalizeStoryboard(Storyboard storyboard) {
        StoryboardNormalizer.normalize(storyboard);
    }

    private void normalizeListFields(StoryboardObject object) {
        object.setConstraints(safeMutable(object.getConstraints()));
    }

    private StoryboardCoordinateBounds normalizeBounds(StoryboardCoordinateBounds bounds, String sceneMode) {
        if (bounds == null || !bounds.hasData()) {
            return null;
        }
        bounds.setX(normalizeAxis(bounds.getX()));
        bounds.setY(normalizeAxis(bounds.getY()));
        if ("3d".equals(sceneMode)) {
            bounds.setZ(normalizeAxis(bounds.getZ()));
        } else {
            bounds.setZ(null);
        }
        if (bounds.getPadding() == null) {
            bounds.setPadding(StoryboardCoordinateBounds.DEFAULT_PADDING);
        }
        return bounds;
    }

    private StoryboardCoordinateBoundsAxis normalizeAxis(StoryboardCoordinateBoundsAxis axis) {
        if (axis == null || !axis.hasData()) {
            return null;
        }
        Double min = axis.getMin() != null ? axis.getMin() : axis.getMax();
        Double max = axis.getMax() != null ? axis.getMax() : axis.getMin();
        if (min != null && max != null && min > max) {
            double tmp = min;
            min = max;
            max = tmp;
        }
        return new StoryboardCoordinateBoundsAxis(min, max);
    }

    private List<String> collectPaletteColors(StoryboardScene scene) {
        List<String> colors = new ArrayList<>();
        for (StoryboardObject object : safeList(scene.getEnteringObjects())) {
            collectStyleColors(object.getStyle(), colors);
        }
        for (StoryboardObject object : safeList(scene.getPersistentObjects())) {
            collectStyleColors(object.getStyle(), colors);
        }
        return colors;
    }

    private void collectStyleColors(StoryboardStyle style, List<String> colors) {
        if (style == null) {
            return;
        }
        addColor(colors, style.getColor());
        addColor(colors, style.getFillColor());
        addColor(colors, style.getStrokeColor());
        addColor(colors, style.getHighlightColor());
    }

    private void addColor(List<String> colors, String color) {
        if (StringUtils.hasText(color) && !colors.contains(color)) {
            colors.add(color);
        }
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

    private StoryboardObject copyObject(StoryboardObject object) {
        if (object == null) {
            return null;
        }
        return objectMapper.convertValue(object, StoryboardObject.class);
    }

    private String objectId(StoryboardObject object) {
        return object != null ? sanitizeIdentifier(object.getId(), "obj") : null;
    }

    private String sanitizeIdentifier(String raw, String fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        String cleaned = NON_IDENTIFIER_CHARS.matcher(raw.trim()).replaceAll("_");
        cleaned = cleaned.replaceAll("_+", "_");
        if (cleaned.startsWith("_")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.endsWith("_")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (!StringUtils.hasText(cleaned)) {
            cleaned = fallback;
        }
        if (!Character.isLetter(cleaned.charAt(0)) && cleaned.charAt(0) != '_') {
            cleaned = fallback + "_" + cleaned;
        }
        return cleaned;
    }

    private String normalizeSceneMode(String sceneMode) {
        String normalized = sceneMode == null ? "" : sceneMode.trim().toLowerCase(Locale.ROOT);
        if ("3d".equals(normalized) || "three_d".equals(normalized) || "three-dimensional".equals(normalized)) {
            return "3d";
        }
        return "2d";
    }

    private String formatPlacement(StoryboardPlacement placement) {
        if (placement == null) {
            return "{}";
        }
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(placement.getPositioning())) {
            parts.add("positioning=" + placement.getPositioning());
        }
        appendAxis(parts, "x", placement.getX());
        appendAxis(parts, "y", placement.getY());
        appendAxis(parts, "z", placement.getZ());
        return "{" + String.join(", ", parts) + "}";
    }

    private void appendAxis(List<String> parts, String name, Narrative.StoryboardPlacementAxis axis) {
        if (axis == null || !axis.hasData()) {
            return;
        }
        if (axis.getValue() != null) {
            parts.add(name + "=" + axis.getValue());
        } else {
            parts.add(name + "=[" + axis.getMin() + "," + axis.getMax() + "]");
        }
    }

    private String formatStyle(StoryboardStyle style) {
        List<String> parts = new ArrayList<>();
        appendStyle(parts, "color", style.getColor());
        appendStyle(parts, "fill_color", style.getFillColor());
        appendStyle(parts, "stroke_color", style.getStrokeColor());
        appendStyle(parts, "highlight_color", style.getHighlightColor());
        appendStyle(parts, "opacity", style.getOpacity());
        appendStyle(parts, "z_index", style.getZIndex());
        return "{" + String.join(", ", parts) + "}";
    }

    private void appendStyle(List<String> parts, String key, Object value) {
        if (value != null) {
            parts.add(key + "=" + value);
        }
    }

    private void appendRollingTurn(List<RollingTurn> turns, String userPrompt, String assistantText) {
        turns.add(new RollingTurn(userPrompt, assistantText));
    }

    private int sceneNumber(String sceneId) {
        if (sceneId == null) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(sceneId.replaceAll("\\D+", ""));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON: " + e.getMessage(), e);
        }
    }

    private <T> List<T> safeList(List<T> list) {
        return list != null ? list : List.of();
    }

    private <T> List<T> safeMutable(List<T> list) {
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    public static final class Result {
        private final Narrative narrative;
        private final int apiCalls;
        private final int expectedScenes;
        private final String sceneMode;

        private Result(Narrative narrative, int apiCalls, int expectedScenes, String sceneMode) {
            this.narrative = narrative;
            this.apiCalls = apiCalls;
            this.expectedScenes = expectedScenes;
            this.sceneMode = sceneMode;
        }

        public Narrative getNarrative() {
            return narrative;
        }

        public int getApiCalls() {
            return apiCalls;
        }

        public int getExpectedScenes() {
            return expectedScenes;
        }

        public String getSceneMode() {
            return sceneMode;
        }
    }

    private static final class DesignState {
        private final String sceneMode;
        private final String outputTarget;
        private final List<StoryboardScene> scenes = new ArrayList<>();
        private final List<StoryboardObject> objectRegistry = new ArrayList<>();
        private final Map<String, StoryboardObject> registryById = new LinkedHashMap<>();
        private final Map<String, StoryboardObject> visibleObjects = new LinkedHashMap<>();
        private Map<String, StoryboardObject> visibleObjectsSnapshot = new LinkedHashMap<>();
        private final Set<String> palette = new LinkedHashSet<>();
        private final CoordinateBoundsAccumulator boundsAccumulator = new CoordinateBoundsAccumulator();

        private DesignState(String sceneMode, String outputTarget) {
            this.sceneMode = sceneMode;
            this.outputTarget = outputTarget;
        }
    }

    private static final class SceneDesignResult {
        private final StoryboardScene scene;
        private final List<StoryboardObject> newObjects;
        private final StoryboardCoordinateBounds coordinateBoundsUpdate;
        private final List<String> paletteColors;
        private JsonNode payload;
        private String userPrompt;
        private int apiCalls;

        private SceneDesignResult(StoryboardScene scene,
                                  List<StoryboardObject> newObjects,
                                  StoryboardCoordinateBounds coordinateBoundsUpdate,
                                  List<String> paletteColors) {
            this.scene = scene;
            this.newObjects = newObjects != null ? newObjects : List.of();
            this.coordinateBoundsUpdate = coordinateBoundsUpdate;
            this.paletteColors = paletteColors != null ? paletteColors : List.of();
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

    private static final class CoordinateBoundsAccumulator {
        private Double xMin;
        private Double xMax;
        private Double yMin;
        private Double yMax;
        private Double zMin;
        private Double zMax;

        private void merge(StoryboardCoordinateBounds bounds) {
            if (bounds == null) {
                return;
            }
            mergeAxis('x', bounds.getX());
            mergeAxis('y', bounds.getY());
            mergeAxis('z', bounds.getZ());
        }

        private void mergeAxis(char axisName, StoryboardCoordinateBoundsAxis axis) {
            if (axis == null || !axis.hasData()) {
                return;
            }
            Double min = axis.getMin() != null ? axis.getMin() : axis.getMax();
            Double max = axis.getMax() != null ? axis.getMax() : axis.getMin();
            if (min == null || max == null) {
                return;
            }
            if (min > max) {
                double tmp = min;
                min = max;
                max = tmp;
            }
            if (axisName == 'x') {
                xMin = xMin == null ? min : Math.min(xMin, min);
                xMax = xMax == null ? max : Math.max(xMax, max);
            } else if (axisName == 'y') {
                yMin = yMin == null ? min : Math.min(yMin, min);
                yMax = yMax == null ? max : Math.max(yMax, max);
            } else if (axisName == 'z') {
                zMin = zMin == null ? min : Math.min(zMin, min);
                zMax = zMax == null ? max : Math.max(zMax, max);
            }
        }

        private StoryboardCoordinateBounds toStoryboardBounds() {
            if (xMin == null && yMin == null && zMin == null) {
                return defaultBounds();
            }
            StoryboardCoordinateBounds bounds = new StoryboardCoordinateBounds();
            bounds.setPadding(StoryboardCoordinateBounds.DEFAULT_PADDING);
            bounds.setX(axisOrDefault(xMin, xMax, -1.0D, 1.0D));
            bounds.setY(axisOrDefault(yMin, yMax, -1.0D, 1.0D));
            if (zMin != null || zMax != null) {
                bounds.setZ(axisOrDefault(zMin, zMax, 0.0D, 0.0D));
            }
            return withPadding(bounds);
        }

        private StoryboardCoordinateBounds defaultBounds() {
            StoryboardCoordinateBounds bounds = new StoryboardCoordinateBounds();
            bounds.setPadding(StoryboardCoordinateBounds.DEFAULT_PADDING);
            bounds.setX(new StoryboardCoordinateBoundsAxis(-7.0D, 7.0D));
            bounds.setY(new StoryboardCoordinateBoundsAxis(-4.0D, 4.0D));
            return bounds;
        }

        private StoryboardCoordinateBounds withPadding(StoryboardCoordinateBounds bounds) {
            double padding = bounds.getPadding() != null ? bounds.getPadding() : StoryboardCoordinateBounds.DEFAULT_PADDING;
            bounds.setX(pad(bounds.getX(), padding));
            bounds.setY(pad(bounds.getY(), padding));
            bounds.setZ(pad(bounds.getZ(), padding));
            return bounds;
        }

        private StoryboardCoordinateBoundsAxis pad(StoryboardCoordinateBoundsAxis axis, double padding) {
            if (axis == null || !axis.hasData()) {
                return null;
            }
            Double min = axis.getMin();
            Double max = axis.getMax();
            if (min == null || max == null) {
                return axis;
            }
            return new StoryboardCoordinateBoundsAxis(min - padding, max + padding);
        }

        private StoryboardCoordinateBoundsAxis axisOrDefault(Double min,
                                                             Double max,
                                                             double defaultMin,
                                                             double defaultMax) {
            return new StoryboardCoordinateBoundsAxis(
                    min != null ? min : defaultMin,
                    max != null ? max : defaultMax);
        }
    }
}
