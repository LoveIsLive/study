package com.kwang.study.mathvision.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.ai.MathVisionAiChatService;
import com.kwang.study.mathvision.workflow.model.AiContentPart;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.model.CodeResult;
import com.kwang.study.mathvision.workflow.model.Narrative;
import com.kwang.study.mathvision.workflow.model.Narrative.Storyboard;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardConstraint;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardCoordinateBounds;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardCoordinateBoundsAxis;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardObject;
import com.kwang.study.mathvision.workflow.model.Narrative.StoryboardScene;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.SceneCodeEntry;
import com.kwang.study.mathvision.workflow.model.StageGenerationRequest;
import com.kwang.study.mathvision.workflow.prompt.CodeGenerationPrompts;
import com.kwang.study.mathvision.workflow.prompt.NarrativePrompts;
import com.kwang.study.mathvision.workflow.prompt.StoryboardJsonBuilder;
import com.kwang.study.mathvision.workflow.prompt.SystemPrompts;
import com.kwang.study.mathvision.workflow.prompt.ToolSchemas;
import com.kwang.study.mathvision.workflow.util.CoordinateBoundsUtils;
import com.kwang.study.mathvision.workflow.util.GeoGebraCodeUtils;
import com.kwang.study.mathvision.workflow.util.ManimCodeUtils;
import com.kwang.study.mathvision.workflow.util.ProblemBundleContextBuilder;
import com.kwang.study.mathvision.workflow.util.SceneModeUtils;
import com.kwang.study.mathvision.workflow.util.StoryboardConstraintCatalog;
import com.kwang.study.mathvision.workflow.util.StoryboardConstraintUtils;
import com.kwang.study.mathvision.workflow.util.StoryboardPatchResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CodeGenerationNode {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationNode.class);
    static final String MANIM_SCENE_METHODS_MARKER = "# __SCENE_METHODS__";

    private static final Pattern FENCED_CODE = Pattern.compile(
            "(?is)```+\\s*(?:python|py|geogebra|ggb|text)?\\s*\\R?([\\s\\S]*?)\\R?```+");
    private static final Pattern MANIM_SCENE_CLASS = Pattern.compile(
            "class\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*Scene[^)]*)\\)\\s*:");

    private final MathVisionAiChatService aiChatService;
    private final ObjectMapper objectMapper;

    public CodeGenerationNode(MathVisionAiChatService aiChatService,
                              ObjectMapper objectMapper) {
        this.aiChatService = aiChatService;
        this.objectMapper = objectMapper;
    }
    public Result run(MathVisionTask task,
                      ProblemBundle bundle,
                      Narrative narrative,
                      MathVisionStageExecutionContext context) {
        return run(task, bundle, narrative, StageGenerationRequest.initialGeneration(), context);
    }

    public Result run(MathVisionTask task,
                      ProblemBundle bundle,
                      Narrative narrative,
                      StageGenerationRequest<CodeResult> request,
                      MathVisionStageExecutionContext context) {
        StageGenerationRequest<CodeResult> resolvedRequest = request != null
                ? request
                : StageGenerationRequest.initialGeneration();
        validateRequest(resolvedRequest);
        if (context != null) {
            context.checkCanceled();
        }
        Instant start = Instant.now();
        String outputTarget = StringUtils.hasText(task.getOutputTarget()) ? task.getOutputTarget() : "manim";
        boolean isGeoGebra = "geogebra".equalsIgnoreCase(outputTarget);
        String sceneMode = SceneModeUtils.normalize(bundle != null ? bundle.getSceneMode() : null);

        List<AiMessage> baseMessages = buildBaseMessages(bundle, narrative, outputTarget, resolvedRequest);
        int[] apiCalls = new int[]{0};

        Storyboard storyboard = narrative != null ? narrative.getStoryboard() : null;
        boolean multiScene = storyboard != null
                && storyboard.getScenes() != null
                && storyboard.getScenes().size() > 1;

        String artifactName = isGeoGebra
                ? GeoGebraCodeUtils.EXPECTED_FIGURE_NAME
                : ManimCodeUtils.EXPECTED_SCENE_NAME;
        String description;
        String generatedCode;
        PerSceneDraft perSceneDraft = null;

        if (resolvedRequest.isUserRevision()) {
            log.debug("MathVision CodeGeneration complete-code revision, taskId={}, target={}",
                    task.getId(), outputTarget);
            SingleSceneDraft draft = reviseCompleteCode(
                    task, baseMessages, narrative, outputTarget, resolvedRequest, context, apiCalls);
            generatedCode = draft.code;
            if (StringUtils.hasText(draft.artifactName)) {
                artifactName = draft.artifactName;
            }
            description = StringUtils.hasText(draft.description)
                    ? draft.description
                    : (isGeoGebra
                        ? "Complete GeoGebra construction revised from existing generated code"
                        : "Complete Manim animation revised from existing generated code");
        } else if (multiScene) {
            log.debug("MathVision CodeGeneration 逐场景生成, taskId={}, scenes={}, target={}",
                    task.getId(), storyboard.getScenes().size(), outputTarget);
            perSceneDraft = generatePerScene(
                    task, baseMessages, storyboard, isGeoGebra, sceneMode, context, apiCalls);
            generatedCode = perSceneDraft.generatedCode;
            description = isGeoGebra
                    ? "Per-scene GeoGebra construction generated from approved storyboard"
                    : "Per-scene Manim animation generated from approved storyboard";
        } else {
            SingleSceneDraft draft = generateSingleScene(
                    task, baseMessages, narrative, outputTarget, context, apiCalls);
            generatedCode = draft.code;
            if (StringUtils.hasText(draft.artifactName)) {
                artifactName = draft.artifactName;
            }
            description = StringUtils.hasText(draft.description)
                    ? draft.description
                    : (isGeoGebra
                        ? "GeoGebra construction generated from approved storyboard"
                        : "Manim animation generated from approved storyboard");
        }

        generatedCode = normalizeCode(generatedCode, outputTarget);
        if (isGeoGebra) {
            generatedCode = GeoGebraCodeUtils.enrichWithSceneButtons(generatedCode, storyboard);
        }
        if (!StringUtils.hasText(generatedCode)) {
            throw new IllegalStateException("AI did not return generated code.");
        }

        CodeResult codeResult = new CodeResult();
        codeResult.setGeneratedCode(generatedCode);
        codeResult.setOutputTarget(outputTarget);
        codeResult.setArtifactFormat(isGeoGebra ? "commands" : "python");
        codeResult.setSceneName(isGeoGebra ? GeoGebraCodeUtils.EXPECTED_FIGURE_NAME : ManimCodeUtils.EXPECTED_SCENE_NAME);
        codeResult.setArtifactName(artifactName);
        codeResult.setDescription(description);
        if (perSceneDraft != null) {
            codeResult.setHeaderCode(perSceneDraft.headerCode);
            codeResult.setSceneEntries(perSceneDraft.sceneEntries);
        }
        codeResult.setToolCalls(apiCalls[0]);
        codeResult.setExecutionTimeSeconds(secondsSince(start));
        return new Result(codeResult, apiCalls[0]);
    }

    private void validateRequest(StageGenerationRequest<CodeResult> request) {
        if (!request.isUserRevision()) {
            return;
        }
        if (!StringUtils.hasText(request.getInstruction())) {
            throw new IllegalArgumentException("User revision instruction cannot be empty.");
        }
        if (request.getExistingArtifact() == null || !request.getExistingArtifact().hasCode()) {
            throw new IllegalArgumentException("Existing generated code is required for user revision.");
        }
    }

    private List<AiMessage> buildBaseMessages(ProblemBundle bundle,
                                              Narrative narrative,
                                              String outputTarget,
                                              StageGenerationRequest<CodeResult> request) {
        String sceneMode = SceneModeUtils.normalize(bundle != null ? bundle.getSceneMode() : null);
        String targetDescription = narrative != null && StringUtils.hasText(narrative.getTargetDescription())
                ? narrative.getTargetDescription()
                : ProblemBundleContextBuilder.workflowTargetDescription(bundle, "", "", outputTarget);
        String compactObjectRegistryJson = narrative != null && narrative.hasStoryboard()
                ? buildCompactObjectRegistryJson(narrative.getStoryboard())
                : "";
        String rulesPrompt = CodeGenerationPrompts.buildRulesPrompt(outputTarget, sceneMode);
        String fixedContext = CodeGenerationPrompts.buildFixedContextPrompt(
                bundle, targetDescription, outputTarget, compactObjectRegistryJson, sceneMode);
        if (request.isUserRevision()) {
            rulesPrompt += "\n\n" + buildRevisionRulesAppendix();
            fixedContext += "\n\n" + buildRevisionFixedContextAppendix(request);
        }
        List<AiMessage> messages = new ArrayList<>();
        messages.add(AiMessage.system(rulesPrompt));
        messages.add(AiMessage.system(fixedContext));
        return messages;
    }

    private SingleSceneDraft generateSingleScene(MathVisionTask task,
                                                 List<AiMessage> baseMessages,
                                                 Narrative narrative,
                                                 String outputTarget,
                                                 MathVisionStageExecutionContext context,
                                                 int[] apiCalls) {
        List<AiMessage> messages = new ArrayList<>(baseMessages);
        String userPrompt = buildSingleSceneGenerationPrompt(narrative, outputTarget);
        messages.add(AiMessage.user(List.of(AiContentPart.text(userPrompt))));
        MathVisionAiChatService.CodeResponse response = aiChatService.requestCode(
                task,
                messages,
                "geogebra".equalsIgnoreCase(outputTarget) ? ToolSchemas.GEOGEBRA_CODE : ToolSchemas.MANIM_CODE,
                preferredCodeFields(outputTarget));
        apiCalls[0] += response.getApiCalls();
        if (context != null) {
            context.checkCanceled();
        }
        JsonNode payload = response.getPayload();
        return new SingleSceneDraft(
                response.getCode(),
                firstTextField(
                        payload,
                        "geogebra".equalsIgnoreCase(outputTarget) ? "figure_name" : "scene_name",
                        "artifactName"),
                textField(payload, "description"));
    }

    private SingleSceneDraft reviseCompleteCode(MathVisionTask task,
                                                List<AiMessage> baseMessages,
                                                Narrative narrative,
                                                String outputTarget,
                                                StageGenerationRequest<CodeResult> request,
                                                MathVisionStageExecutionContext context,
                                                int[] apiCalls) {
        List<AiMessage> messages = new ArrayList<>(baseMessages);
        messages.add(AiMessage.user(List.of(AiContentPart.text(
                buildCompleteCodeRevisionPrompt(narrative, outputTarget, request)))));
        MathVisionAiChatService.CodeResponse response = aiChatService.requestCode(
                task,
                messages,
                "geogebra".equalsIgnoreCase(outputTarget) ? ToolSchemas.GEOGEBRA_CODE : ToolSchemas.MANIM_CODE,
                preferredCodeFields(outputTarget));
        apiCalls[0] += response.getApiCalls();
        if (context != null) {
            context.checkCanceled();
        }
        JsonNode payload = response.getPayload();
        return new SingleSceneDraft(
                response.getCode(),
                firstTextField(
                        payload,
                        "geogebra".equalsIgnoreCase(outputTarget) ? "figure_name" : "scene_name",
                        "artifactName"),
                textField(payload, "description"));
    }

    private PerSceneDraft generatePerScene(MathVisionTask task,
                                           List<AiMessage> baseMessages,
                                           Storyboard storyboard,
                                           boolean isGeoGebra,
                                           String sceneMode,
                                           MathVisionStageExecutionContext context,
                                           int[] apiCalls) {
        Storyboard merged = StoryboardPatchResolver.buildMergedStoryboard(storyboard);
        List<StoryboardScene> scenes = merged != null && merged.getScenes() != null
                ? merged.getScenes()
                : storyboard.getScenes();

        List<String> sceneNames = new ArrayList<>();
        for (int i = 0; i < scenes.size(); i++) {
            StoryboardScene scene = scenes.get(i);
            if (isGeoGebra) {
                String title = StringUtils.hasText(scene.getTitle()) ? scene.getTitle() : "scene_" + (i + 1);
                sceneNames.add("Scene " + (i + 1) + ": " + title);
            } else {
                sceneNames.add(ManimCodeUtils.buildSceneMethodName(scene.getSceneId(), scene.getTitle(), i));
            }
        }

        Map<String, StoryboardObject> enrichedRegistry = buildBaseEnrichedRegistry(storyboard);
        Map<String, StoryboardObject> createdRuntimeObjects = new LinkedHashMap<>();
        Map<String, StoryboardObject> visibleRuntimeObjects = new LinkedHashMap<>();

        String coordinateBoundsBlock = coordinateBoundsImplementationBlock(storyboard, isGeoGebra, true);
        String headerCode = isGeoGebra
                ? staticGeoGebraSkeleton(storyboard, sceneNames)
                : staticManimSkeleton(storyboard, scenes, sceneNames, sceneMode);

        List<SceneEntry> entries = new ArrayList<>();
        List<SceneCodeEntry> sceneEntries = new ArrayList<>();
        for (int i = 0; i < scenes.size(); i++) {
            if (context != null) {
                context.checkCanceled();
            }
            StoryboardScene scene = scenes.get(i);
            String sceneName = sceneNames.get(i);
            String sceneJson;
            try {
                sceneJson = StoryboardJsonBuilder.buildSceneForCodegen(
                        scene, isGeoGebra ? "geogebra" : "manim");
            } catch (Exception e) {
                sceneJson = "{}";
            }

            String constraintSummaryBlock = enrichedRegistry != null
                    ? toBlock(buildSceneConstraintSummary(scene, enrichedRegistry)) : "";
            String runtimeStateBlock = toBlock(buildRuntimeObjectStateBlock(
                    visibleRuntimeObjects, createdRuntimeObjects, scene, enrichedRegistry, isGeoGebra));

            String scenePrompt = (isGeoGebra
                    ? CodeGenerationPrompts.geoGebraSceneCodeUserPrompt(sceneJson, sceneName, i, scenes.size())
                    : CodeGenerationPrompts.manimSceneCodeUserPrompt(sceneJson, sceneName, i, scenes.size()))
                    + coordinateBoundsBlock
                    + runtimeStateBlock
                    + constraintSummaryBlock;

            List<AiMessage> sceneMessages = new ArrayList<>(baseMessages);
            sceneMessages.add(AiMessage.user(List.of(AiContentPart.text(scenePrompt))));

            MathVisionAiChatService.CodeResponse response = aiChatService.requestCode(
                    task,
                    sceneMessages,
                    ToolSchemas.SCENE_CODE,
                    List.of("sceneCode"));
            apiCalls[0] += response.getApiCalls();

            updateRuntimeObjectState(createdRuntimeObjects, visibleRuntimeObjects, scene, enrichedRegistry);
            if (enrichedRegistry != null) {
                applyScenePatches(enrichedRegistry, scene);
            }

            String sceneCode = response.getCode();
            if (!StringUtils.hasText(sceneCode)) {
                throw new IllegalStateException("Scene code generation failed for " + sceneName
                        + (StringUtils.hasText(response.getFailureReason())
                            ? ": " + response.getFailureReason() : ""));
            }
            entries.add(new SceneEntry(sceneName, sceneCode));
            sceneEntries.add(new SceneCodeEntry(
                    i,
                    scene.getSceneId(),
                    sceneName,
                    sceneCode,
                    false));
        }

        String generatedCode = isGeoGebra
                ? assembleGeoGebraPerSceneCode(headerCode, entries)
                : assembleManimPerSceneCode(headerCode, entries);
        return new PerSceneDraft(generatedCode, headerCode, sceneEntries);
    }

    private String assembleGeoGebraPerSceneCode(String headerCode, List<SceneEntry> entries) {
        StringBuilder sb = new StringBuilder(headerCode == null ? "" : headerCode.trim());
        for (SceneEntry entry : entries) {
            String code = GeoGebraCodeUtils.extractCode(entry.code);
            if (!StringUtils.hasText(code)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(code.trim());
        }
        return sb.toString();
    }

    private String assembleManimPerSceneCode(String headerCode, List<SceneEntry> entries) {
        String skeleton = ManimCodeUtils.extractCode(headerCode);
        String methods = buildManimSceneMethods(entries);
        if (!StringUtils.hasText(skeleton)) {
            return methods;
        }
        String normalizedSkeleton = skeleton.replaceAll("(\\R\\s*)+$", "");
        if (normalizedSkeleton.contains(MANIM_SCENE_METHODS_MARKER)) {
            return normalizedSkeleton.replace(MANIM_SCENE_METHODS_MARKER, methods.trim());
        }
        if (!StringUtils.hasText(methods)) {
            return normalizedSkeleton;
        }
        return normalizedSkeleton + "\n\n" + methods;
    }
    private String buildManimSceneMethods(List<SceneEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SceneEntry entry : entries) {
            if (entry == null || !StringUtils.hasText(entry.name)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            String methodName = sanitizeManimMethodName(entry.name);
            String body = normalizeManimSceneMethodBody(entry.code);
            sb.append("    def ").append(methodName).append("(self):\n")
                    .append(indentMethodBody(body));
        }
        return sb.toString();
    }

    private static String sanitizeManimMethodName(String methodName) {
        String normalized = methodName != null ? methodName.trim() : "";
        if (normalized.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return normalized;
        }
        String sanitized = normalized.replaceAll("[^A-Za-z0-9_]", "_")
                .replaceAll("^[^A-Za-z_]+", "")
                .replaceAll("_+", "_");
        return sanitized.isBlank() ? "scene_method" : sanitized;
    }

    private static String normalizeManimSceneMethodBody(String sceneCode) {
        String code = ManimCodeUtils.extractCode(sceneCode);
        if (!StringUtils.hasText(code)) {
            return "pass";
        }
        String normalized = code.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").trim();
        String[] lines = normalized.split("\n", -1);
        int defLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i] != null ? lines[i].trim() : "";
            if (trimmed.matches("def\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\(\\s*self\\s*\\)\\s*:")) {
                defLine = i;
                break;
            }
        }
        if (defLine >= 0) {
            StringBuilder body = new StringBuilder();
            for (int i = defLine + 1; i < lines.length; i++) {
                body.append(lines[i]);
                if (i < lines.length - 1) {
                    body.append('\n');
                }
            }
            normalized = dedentBlock(body.toString()).trim();
        } else {
            normalized = dedentBlock(normalized).trim();
        }
        return normalized.isBlank() ? "pass" : normalized;
    }

    private static String dedentBlock(String block) {
        if (!StringUtils.hasText(block)) {
            return "";
        }
        String normalized = block.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            minIndent = Math.min(minIndent, countLeadingSpaces(line));
        }
        if (minIndent == Integer.MAX_VALUE || minIndent == 0) {
            return normalized;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line != null && line.length() >= minIndent) {
                sb.append(line.substring(minIndent));
            } else if (line != null) {
                sb.append(line.trim());
            }
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String indentMethodBody(String body) {
        String normalized = !StringUtils.hasText(body) ? "pass" : body;
        String[] lines = normalized.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append("        ");
            if (lines[i] != null) {
                sb.append(lines[i]);
            }
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static int countLeadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }
    // ==== Static Manim skeleton (shared coordinate infrastructure) ====

    static String staticManimSkeleton(List<StoryboardScene> scenes,
                                      List<String> sceneMethodNames,
                                      String sceneMode) {
        return staticManimSkeleton(null, scenes, sceneMethodNames, sceneMode);
    }

    static String staticManimSkeleton(Narrative.Storyboard storyboard,
                                      List<StoryboardScene> scenes,
                                      List<String> sceneMethodNames,
                                      String sceneMode) {
        boolean useVoiceover = hasVoiceoverText(scenes);
        boolean useThreeD = SceneModeUtils.isThreeD(sceneMode);
        boolean useThreeDScene = !useVoiceover && useThreeD;
        String baseClass = useVoiceover ? "VoiceoverScene" : (useThreeDScene ? "ThreeDScene" : "Scene");

        StringBuilder sb = new StringBuilder();
        sb.append("from manim import *\n");
        sb.append("import numpy as np\n");
        if (useVoiceover) {
            sb.append("from manim_voiceover import VoiceoverScene\n");
            sb.append("from manim_voiceover.services.gtts import GTTSService\n\n");
            sb.append("VOICEOVER_SPEED = 1.5\n");
        }
        sb.append("\n");
        sb.append("class MainScene(").append(baseClass).append("):\n");
        sb.append("    def construct(self):\n");
        if (useVoiceover) {
            sb.append("        self.set_speech_service(GTTSService(lang=\"zh-CN\", global_speed=VOICEOVER_SPEED))\n");
        }
        sb.append("        self.objects = {}\n");
        sb.append("        self.setup_shared_scene()\n");
        if (sceneMethodNames == null || sceneMethodNames.isEmpty()) {
            sb.append("        self.wait(1)\n");
        } else {
            for (String methodName : sceneMethodNames) {
                sb.append("        self.").append(sanitizeManimMethodName(methodName)).append("()\n");
            }
        }
        sb.append("\n");
        sb.append(buildManimSharedInfrastructure(storyboard, useThreeD));
        sb.append("\n");
        sb.append("    ").append(MANIM_SCENE_METHODS_MARKER);
        return sb.toString();
    }

    private static String buildManimSharedInfrastructure(Narrative.Storyboard storyboard, boolean useThreeD) {
        Narrative.StoryboardCoordinateBounds bounds = storyboard != null
                ? CoordinateBoundsUtils.withPadding(storyboard.getCoordinateBounds())
                : null;
        boolean hasXyBounds = bounds != null && bounds.getX() != null && bounds.getY() != null;
        String xRange = hasXyBounds ? pythonRange(bounds.getX()) : "None";
        String yRange = hasXyBounds ? pythonRange(bounds.getY()) : "None";
        String zRange = hasXyBounds && bounds.getZ() != null ? pythonRange(bounds.getZ()) : "None";
        String axesClass = useThreeD ? "ThreeDAxes" : "Axes";

        StringBuilder sb = new StringBuilder();
        sb.append("    def setup_shared_scene(self):\n");
        sb.append("        self.objects = getattr(self, \"objects\", {})\n");
        sb.append("        self._mv_is_3d = ").append(useThreeD ? "True" : "False").append("\n");
        sb.append("        self._mv_x_range = ").append(xRange).append("\n");
        sb.append("        self._mv_y_range = ").append(yRange).append("\n");
        sb.append("        self._mv_z_range = ").append(zRange).append("\n");
        sb.append("        self._mv_frame_width = 10.5\n");
        sb.append("        self._mv_frame_height = 6.5\n");
        sb.append("        self._mv_frame_depth = 4.0\n");
        sb.append("        self._mv_unit_scale = 1.0\n");
        sb.append("        self._mv_x_length = self._mv_frame_width\n");
        sb.append("        self._mv_y_length = self._mv_frame_height\n");
        sb.append("        self._mv_z_length = self._mv_frame_depth\n");
        sb.append("        self._mv_axes = None\n");
        sb.append("        self.axes = None\n");
        sb.append("        if self._mv_x_range is not None and self._mv_y_range is not None:\n");
        sb.append("            x_span = max(abs(self._mv_x_range[1] - self._mv_x_range[0]), 1.0)\n");
        sb.append("            y_span = max(abs(self._mv_y_range[1] - self._mv_y_range[0]), 1.0)\n");
        sb.append("            scale_candidates = [self._mv_frame_width / x_span, self._mv_frame_height / y_span]\n");
        sb.append("            z_span = 1.0\n");
        sb.append("            if self._mv_is_3d:\n");
        sb.append("                active_z_range = self._mv_z_range if self._mv_z_range is not None else [-1.0, 1.0, 1.0]\n");
        sb.append("                z_span = max(abs(active_z_range[1] - active_z_range[0]), 1.0)\n");
        sb.append("                if self._mv_z_range is not None:\n");
        sb.append("                    scale_candidates.append(self._mv_frame_depth / z_span)\n");
        sb.append("            self._mv_unit_scale = min(scale_candidates)\n");
        sb.append("            self._mv_x_length = x_span * self._mv_unit_scale\n");
        sb.append("            self._mv_y_length = y_span * self._mv_unit_scale\n");
        sb.append("            if self._mv_is_3d:\n");
        sb.append("                self._mv_z_length = z_span * self._mv_unit_scale\n");
        sb.append("            if self._mv_is_3d:\n");
        sb.append("                self.axes = ThreeDAxes(\n");
        sb.append("                    x_range=self._mv_x_range,\n");
        sb.append("                    y_range=self._mv_y_range,\n");
        sb.append("                    z_range=self._mv_z_range if self._mv_z_range is not None else [-1.0, 1.0, 1.0],\n");
        sb.append("                    x_length=self._mv_x_length,\n");
        sb.append("                    y_length=self._mv_y_length,\n");
        sb.append("                    z_length=self._mv_z_length,\n");
        sb.append("                )\n");
        sb.append("            else:\n");
        sb.append("                self.axes = ").append(axesClass).append("(\n");
        sb.append("                    x_range=self._mv_x_range,\n");
        sb.append("                    y_range=self._mv_y_range,\n");
        sb.append("                    x_length=self._mv_x_length,\n");
        sb.append("                    y_length=self._mv_y_length,\n");
        sb.append("                    tips=False,\n");
        sb.append("                )\n");
        sb.append("            self._mv_axes = self.axes\n\n");

        sb.append("    def world_point(self, x, y=0.0, z=0.0):\n");
        sb.append("        if self._mv_axes is None:\n");
        sb.append("            return np.array([x, y, z], dtype=float)\n");
        sb.append("        if self._mv_is_3d:\n");
        sb.append("            return self._mv_axes.c2p(x, y, z)\n");
        sb.append("        return self._mv_axes.c2p(x, y)\n\n");

        sb.append("    def c2p(self, x, y=0.0, z=0.0):\n");
        sb.append("        return self.world_point(x, y, z)\n\n");

        sb.append("    def world_vector(self, dx, dy=0.0, dz=0.0):\n");
        sb.append("        return self.world_point(dx, dy, dz) - self.world_point(0.0, 0.0, 0.0)\n\n");

        sb.append("    def world_radius(self, radius):\n");
        sb.append("        return abs(radius) * getattr(self, '_mv_unit_scale', 1.0)\n\n");

        sb.append("    def world_circle(self, x, y, radius, **kwargs):\n");
        sb.append("        circle = Circle(radius=self.world_radius(radius), **kwargs)\n");
        sb.append("        circle.move_to(self.c2p(x, y))\n");
        sb.append("        return circle\n\n");

        sb.append("    def world_arc(self, x, y, radius, start_angle=0.0, angle=TAU, **kwargs):\n");
        sb.append("        return Arc(radius=self.world_radius(radius), start_angle=start_angle,\n");
        sb.append("                   angle=angle, arc_center=self.c2p(x, y), **kwargs)\n\n");

        sb.append("    def register_object(self, object_id, mobject):\n");
        sb.append("        self.objects[str(object_id)] = mobject\n");
        sb.append("        return mobject\n\n");

        sb.append("    def get_object(self, object_id):\n");
        sb.append("        return self.objects[str(object_id)]\n\n");

        sb.append("    def has_object(self, object_id):\n");
        sb.append("        return str(object_id) in self.objects\n\n");

        sb.append("    def remove_registered_object(self, object_id):\n");
        sb.append("        mobject = self.objects.get(str(object_id))\n");
        sb.append("        if mobject is not None:\n");
        sb.append("            self.remove(mobject)\n");
        sb.append("        return mobject\n");
        return sb.toString();
    }

    private static boolean hasVoiceoverText(List<StoryboardScene> scenes) {
        if (scenes == null) {
            return false;
        }
        for (StoryboardScene scene : scenes) {
            if (scene == null || scene.getActions() == null) {
                continue;
            }
            for (Narrative.StoryboardAction action : scene.getActions()) {
                if (action != null
                        && action.getVoiceoverText() != null
                        && !action.getVoiceoverText().isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String pythonRange(Narrative.StoryboardCoordinateBoundsAxis axis) {
        Narrative.StoryboardCoordinateBoundsAxis normalized = CoordinateBoundsUtils.normalizeAxis(axis);
        if (normalized == null || normalized.getMin() == null || normalized.getMax() == null) {
            return "None";
        }
        return "[" + pythonNumber(normalized.getMin())
                + ", " + pythonNumber(normalized.getMax())
                + ", " + pythonNumber(resolveManimAxisStep(normalized)) + "]";
    }

    private static double resolveManimAxisStep(Narrative.StoryboardCoordinateBoundsAxis axis) {
        double span = Math.abs(axis.getMax() - axis.getMin());
        if (span <= 0.0) {
            return 1.0;
        }
        double rough = span / 8.0;
        if (rough <= 0.25) {
            return 0.25;
        }
        if (rough <= 0.5) {
            return 0.5;
        }
        if (rough <= 1.0) {
            return 1.0;
        }
        return Math.ceil(rough);
    }

    private static String pythonNumber(double value) {
        double rounded = Math.round(value * 1_000_000.0) / 1_000_000.0;
        if (Math.rint(rounded) == rounded) {
            return String.format(java.util.Locale.ROOT, "%.1f", rounded);
        }
        return String.format(java.util.Locale.ROOT, "%.6f", rounded)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", ".0");
    }

    private String coordinateBoundsImplementationBlock(Narrative.Storyboard storyboard,
                                                       boolean isGeoGebra,
                                                       boolean staticSkeletonOwnsManimBounds) {
        if (storyboard == null || storyboard.getCoordinateBounds() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nCoordinate bounds implementation contract:\n");
        sb.append("- ").append(CoordinateBoundsUtils.format(storyboard.getCoordinateBounds())).append("\n");
        sb.append("- These are storyboard world-coordinate bounds. Preserve storyboard coordinates; do not rewrite them merely to fit a render frame.\n");
        sb.append("- Absolutely positioned storyboard objects must be strictly inside these bounds; an object exactly on a min/max boundary is out of bounds.\n");
        if (isGeoGebra) {
            sb.append("- Use `")
                    .append(CoordinateBoundsUtils.toGeoGebraSetCoordSystem(storyboard.getCoordinateBounds()))
                    .append("` as the exact initial GeoGebra view command; it must appear in the generated script before scene-specific construction commands.\n");
        } else if (staticSkeletonOwnsManimBounds) {
            sb.append("- The static Manim skeleton has already defined shared coordinate helpers: `self.world_point(x, y, z=0)`, `self.c2p(x, y, z=0)`, `self.world_vector(dx, dy, dz=0)`, `self.world_radius(r)`, `self.world_circle(x, y, r, **kwargs)`, `self.world_arc(x, y, r, ...)`, and `self.axes`/`self._mv_axes` when bounds are available.\n");
            sb.append("- The static Manim skeleton maps storyboard x/y units with one uniform screen scale derived from these bounds, so equal world lengths render equally and the padded bounds fit inside the Manim frame. Do not override `x_length`, `y_length`, `_mv_unit_scale`, or the shared coordinate helper methods in scene code.\n");
            sb.append("- Use those skeleton helpers for storyboard world geometry. Do not redefine axes, imports, MainScene, construct(), or coordinate helper methods in the scene body.\n");
            sb.append("- For world-coordinate circles, arcs, and any geometric radius/length parameter from the storyboard, convert the value with `self.world_radius(r)` or use `self.world_circle(...)` / `self.world_arc(...)`; do not pass a storyboard radius directly as raw Manim frame units.\n");
            sb.append("- Do not place storyboard world-coordinate objects with raw scene coordinates such as `Dot([x, y, 0])`, `Line([x1, y1, 0], [x2, y2, 0])`, or `.move_to([x, y, 0])`.\n");
            sb.append("- Choose render-frame placement for titles, callouts, and fixed overlays at this stage; storyboard placement itself remains world/relative positioning.\n");
        } else {
            sb.append("- For Manim, this is the required coordinate-system boundary: define a shared `Axes`, `NumberPlane`, or for 3D `ThreeDAxes`/equivalent from these ranges using one uniform x/y unit scale so equal world lengths render equally and the padded bounds fit inside the frame. Map storyboard world geometry with `axes.c2p(...)` or a clearly named helper wrapping `c2p`; raw Manim frame coordinates are allowed only for fixed overlays, titles, camera/UI placement, and other non-storyboard-geometry elements.\n");
            sb.append("- For world-coordinate circles, arcs, and any geometric radius/length parameter from the storyboard, convert the value through the same uniform unit scale; do not pass a storyboard radius directly as raw Manim frame units.\n");
            sb.append("- Do not place storyboard world-coordinate objects with raw scene coordinates such as `Dot([x, y, 0])`, `Line([x1, y1, 0], [x2, y2, 0])`, or `.move_to([x, y, 0])`.\n");
            sb.append("- Choose render-frame placement for titles, callouts, and fixed overlays at this stage; storyboard placement itself remains world/relative positioning.\n");
        }
        return sb.toString();
    }

    private String staticGeoGebraSkeleton(Narrative.Storyboard storyboard, List<String> sceneSectionNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("# GeoGebra command script\n");
        sb.append(CoordinateBoundsUtils.toGeoGebraSetCoordSystem(
                storyboard != null ? storyboard.getCoordinateBounds() : null));
        if (sceneSectionNames != null && !sceneSectionNames.isEmpty()) {
            sb.append("\n# Scene sequence: ");
            sb.append(String.join(" | ", sceneSectionNames));
        }
        return sb.toString();
    }
    // ==== enriched registry + scene patch application ====

    private Map<String, StoryboardObject> buildBaseEnrichedRegistry(Storyboard storyboard) {
        if (storyboard == null || storyboard.getObjectRegistry() == null
                || storyboard.getObjectRegistry().isEmpty()) {
            return null;
        }
        Map<String, StoryboardObject> enriched = new LinkedHashMap<>();
        for (StoryboardObject obj : storyboard.getObjectRegistry()) {
            String id = StoryboardPatchResolver.objectId(obj);
            if (id != null) {
                enriched.put(id, StoryboardPatchResolver.copyObject(obj));
            }
        }
        return enriched.isEmpty() ? null : enriched;
    }

    private void applyScenePatches(Map<String, StoryboardObject> enriched, StoryboardScene scene) {
        if (enriched == null || scene == null) {
            return;
        }
        List<StoryboardObject> patches = new ArrayList<>();
        if (scene.getEnteringObjects() != null) {
            patches.addAll(scene.getEnteringObjects());
        }
        if (scene.getPersistentObjects() != null) {
            patches.addAll(scene.getPersistentObjects());
        }
        for (StoryboardObject patch : patches) {
            String id = StoryboardPatchResolver.objectId(patch);
            if (id == null) {
                continue;
            }
            StoryboardObject target = enriched.get(id);
            if (target == null) {
                continue;
            }
            if (patch.getStyle() != null && patch.getStyle().hasData()) {
                target.setStyle(patch.getStyle());
            }
            if (patch.getPlacement() != null && patch.getPlacement().hasData()) {
                target.setPlacement(patch.getPlacement());
            }
        }
    }
    // ==== runtime object lifecycle tracking across scenes ====

    private String buildRuntimeObjectStateBlock(Map<String, StoryboardObject> visibleRuntimeObjects,
                                                Map<String, StoryboardObject> createdRuntimeObjects,
                                                StoryboardScene scene,
                                                Map<String, StoryboardObject> enrichedRegistry,
                                                boolean isGeoGebra) {
        String handleName = isGeoGebra ? "the existing GeoGebra object name" : "self.objects[\"id\"]";
        StringBuilder sb = new StringBuilder();
        sb.append("Runtime object state before this scene (authoritative for object reuse):\n");
        appendRuntimeObjectList(sb, "currently_visible", visibleRuntimeObjects);

        Map<String, StoryboardObject> invisibleCreated = new LinkedHashMap<>();
        if (createdRuntimeObjects != null) {
            for (Map.Entry<String, StoryboardObject> entry : createdRuntimeObjects.entrySet()) {
                if (entry.getKey() != null
                        && (visibleRuntimeObjects == null || !visibleRuntimeObjects.containsKey(entry.getKey()))) {
                    invisibleCreated.put(entry.getKey(), entry.getValue());
                }
            }
        }
        appendRuntimeObjectList(sb, "already_created_but_currently_invisible", invisibleCreated);

        sb.append("Reuse rule: every id in `currently_visible` or `already_created_but_currently_invisible` ")
                .append("has already been created. Reuse ")
                .append(handleName)
                .append(" for those ids; do not construct a replacement object with the same storyboard id.\n");
        if (!isGeoGebra) {
            sb.append("Exit rule: when an existing object exits, remove it from the scene visually but keep its ")
                    .append("`self.objects[id]` reference so a later scene can re-add or transform the same mobject.\n");
        }
        appendSceneLifecycleGuidance(sb, scene, visibleRuntimeObjects, createdRuntimeObjects, enrichedRegistry);
        return sb.toString().trim();
    }

    private void appendRuntimeObjectList(StringBuilder sb, String label, Map<String, StoryboardObject> objects) {
        sb.append(label).append(":\n");
        if (objects == null || objects.isEmpty()) {
            sb.append("- none\n");
            return;
        }
        for (StoryboardObject obj : objects.values()) {
            if (obj != null && StringUtils.hasText(obj.getId())) {
                sb.append("- ").append(obj.getId()).append("\n");
            }
        }
    }

    private void appendSceneLifecycleGuidance(StringBuilder sb,
                                              StoryboardScene scene,
                                              Map<String, StoryboardObject> visibleRuntimeObjects,
                                              Map<String, StoryboardObject> createdRuntimeObjects,
                                              Map<String, StoryboardObject> enrichedRegistry) {
        if (scene == null) {
            return;
        }
        sb.append("Scene lifecycle guidance:\n");
        appendLifecyclePatchGuidance(sb, "persistent_objects", scene.getPersistentObjects(),
                visibleRuntimeObjects, createdRuntimeObjects, enrichedRegistry);
        appendLifecyclePatchGuidance(sb, "entering_objects", scene.getEnteringObjects(),
                visibleRuntimeObjects, createdRuntimeObjects, enrichedRegistry);
        appendLifecyclePatchGuidance(sb, "exiting_objects", scene.getExitingObjects(),
                visibleRuntimeObjects, createdRuntimeObjects, enrichedRegistry);
    }

    private void appendLifecyclePatchGuidance(StringBuilder sb,
                                              String fieldName,
                                              List<StoryboardObject> patches,
                                              Map<String, StoryboardObject> visibleRuntimeObjects,
                                              Map<String, StoryboardObject> createdRuntimeObjects,
                                              Map<String, StoryboardObject> enrichedRegistry) {
        if (patches == null || patches.isEmpty()) {
            sb.append("- ").append(fieldName).append(": none\n");
            return;
        }
        List<String> parts = new ArrayList<>();
        for (StoryboardObject patch : patches) {
            String id = StoryboardPatchResolver.objectId(patch);
            if (id == null) {
                continue;
            }
            boolean currentlyVisible = visibleRuntimeObjects != null && visibleRuntimeObjects.containsKey(id);
            boolean alreadyCreated = currentlyVisible
                    || (createdRuntimeObjects != null && createdRuntimeObjects.containsKey(id));
            boolean knownInRegistry = enrichedRegistry != null && enrichedRegistry.containsKey(id);
            String status;
            if (currentlyVisible) {
                status = "reuse visible object";
            } else if (alreadyCreated) {
                status = "re-add existing invisible object";
            } else if (knownInRegistry) {
                status = "first-time creation from storyboard id";
            } else {
                status = "unknown id; preserve storyboard id if possible";
            }
            parts.add(id + " (" + status + ")");
        }
        if (parts.isEmpty()) {
            sb.append("- ").append(fieldName).append(": none\n");
        } else {
            sb.append("- ").append(fieldName).append(": ")
                    .append(String.join(", ", parts)).append("\n");
        }
    }

    private void updateRuntimeObjectState(Map<String, StoryboardObject> createdRuntimeObjects,
                                          Map<String, StoryboardObject> visibleRuntimeObjects,
                                          StoryboardScene scene,
                                          Map<String, StoryboardObject> enrichedRegistry) {
        if (scene == null) {
            return;
        }
        mergeRuntimeObjects(createdRuntimeObjects, visibleRuntimeObjects,
                scene.getPersistentObjects(), enrichedRegistry, true);
        mergeRuntimeObjects(createdRuntimeObjects, visibleRuntimeObjects,
                scene.getEnteringObjects(), enrichedRegistry, true);
        mergeRuntimeObjects(createdRuntimeObjects, visibleRuntimeObjects,
                scene.getExitingObjects(), enrichedRegistry, false);
        removeRuntimeVisibleObjects(visibleRuntimeObjects, scene.getExitingObjects());
    }

    private void mergeRuntimeObjects(Map<String, StoryboardObject> createdRuntimeObjects,
                                     Map<String, StoryboardObject> visibleRuntimeObjects,
                                     List<StoryboardObject> patches,
                                     Map<String, StoryboardObject> enrichedRegistry,
                                     boolean visibleAfterScene) {
        if (patches == null || createdRuntimeObjects == null || visibleRuntimeObjects == null) {
            return;
        }
        for (StoryboardObject patch : patches) {
            String id = StoryboardPatchResolver.objectId(patch);
            if (id == null) {
                continue;
            }
            StoryboardObject merged = StoryboardPatchResolver.copyObject(visibleRuntimeObjects.get(id));
            if (merged == null) {
                merged = StoryboardPatchResolver.copyObject(createdRuntimeObjects.get(id));
            }
            if (merged == null && enrichedRegistry != null) {
                merged = StoryboardPatchResolver.copyObject(enrichedRegistry.get(id));
            }
            if (merged == null) {
                merged = new StoryboardObject();
                merged.setId(id);
            }
            applyRuntimePatch(merged, patch);
            createdRuntimeObjects.put(id, StoryboardPatchResolver.copyObject(merged));
            if (visibleAfterScene) {
                visibleRuntimeObjects.put(id, StoryboardPatchResolver.copyObject(merged));
            }
        }
    }

    private void removeRuntimeVisibleObjects(Map<String, StoryboardObject> visibleRuntimeObjects,
                                             List<StoryboardObject> exitingObjects) {
        if (visibleRuntimeObjects == null || exitingObjects == null) {
            return;
        }
        for (StoryboardObject exitingObject : exitingObjects) {
            String id = StoryboardPatchResolver.objectId(exitingObject);
            if (id != null) {
                visibleRuntimeObjects.remove(id);
            }
        }
    }

    private void applyRuntimePatch(StoryboardObject target, StoryboardObject patch) {
        if (target == null || patch == null) {
            return;
        }
        String id = StoryboardPatchResolver.objectId(patch);
        if (id != null) {
            target.setId(id);
        }
        StoryboardObject patchCopy = StoryboardPatchResolver.copyObject(patch);
        if (patch.getPlacement() != null && patch.getPlacement().hasData()) {
            target.setPlacement(patchCopy != null ? patchCopy.getPlacement() : patch.getPlacement());
        }
        if (patch.getStyle() != null && patch.getStyle().hasData()) {
            target.setStyle(patchCopy != null ? patchCopy.getStyle() : patch.getStyle());
        }
    }
    // ==== per-scene constraint summary (self-contained; reads constraint fields directly) ====

    private String buildSceneConstraintSummary(StoryboardScene scene,
                                               Map<String, StoryboardObject> enrichedRegistry) {
        if (scene == null || enrichedRegistry == null || enrichedRegistry.isEmpty()) {
            return "";
        }
        Map<String, String> idToKind = new LinkedHashMap<>();
        collectSceneObjectIds(scene, idToKind, enrichedRegistry);
        if (idToKind.isEmpty()) {
            return "";
        }

        Set<String> allIds = new LinkedHashSet<>(idToKind.keySet());
        for (String id : new ArrayList<>(idToKind.keySet())) {
            collectConstraintReferencedIds(id, enrichedRegistry, allIds);
        }
        for (String id : allIds) {
            StoryboardObject referenced = enrichedRegistry.get(id);
            if (referenced != null) {
                idToKind.putIfAbsent(id,
                        StringUtils.hasText(referenced.getKind()) ? referenced.getKind() : "?");
            }
        }

        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : idToKind.entrySet()) {
            StoryboardObject obj = enrichedRegistry.get(entry.getKey());
            if (obj == null || obj.getConstraints() == null) {
                continue;
            }
            for (StoryboardConstraint c : obj.getConstraints()) {
                String line = formatConstraintLine(entry.getKey(), entry.getValue(), c);
                if (StringUtils.hasText(line)) {
                    lines.add(line);
                }
            }
        }
        if (scene.getConstraints() != null) {
            for (StoryboardConstraint c : scene.getConstraints()) {
                String targetId = extractConstraintTargetId(c);
                String kind = targetId != null ? idToKind.getOrDefault(targetId, "?") : "?";
                String line = formatConstraintLine(targetId != null ? targetId : "scene", kind, c);
                if (StringUtils.hasText(line)) {
                    lines.add(line);
                }
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Constraint summary (HARD invariants for this scene):\n");
        for (String line : lines) {
            sb.append("- ").append(line).append("\n");
        }
        return sb.toString().trim();
    }

    private void collectConstraintReferencedIds(String id,
                                                Map<String, StoryboardObject> enrichedRegistry,
                                                Set<String> allIds) {
        StoryboardObject object = enrichedRegistry.get(id);
        if (object == null || object.getConstraints() == null) {
            return;
        }
        for (StoryboardConstraint constraint : object.getConstraints()) {
            for (String referencedId : StoryboardConstraintUtils.referencedObjectIds(constraint)) {
                if (enrichedRegistry.containsKey(referencedId) && allIds.add(referencedId)) {
                    collectConstraintReferencedIds(referencedId, enrichedRegistry, allIds);
                }
            }
        }
    }

    private void collectSceneObjectIds(StoryboardScene scene,
                                       Map<String, String> idToKind,
                                       Map<String, StoryboardObject> enrichedRegistry) {
        collectObjectIds(scene.getEnteringObjects(), idToKind, enrichedRegistry);
        collectObjectIds(scene.getPersistentObjects(), idToKind, enrichedRegistry);
        collectObjectIds(scene.getExitingObjects(), idToKind, enrichedRegistry);
        if (scene.getActions() != null) {
            for (Narrative.StoryboardAction action : scene.getActions()) {
                if (action.getTargets() == null) {
                    continue;
                }
                for (String targetId : action.getTargets()) {
                    StoryboardObject obj = enrichedRegistry.get(targetId);
                    if (obj != null) {
                        idToKind.putIfAbsent(targetId, obj.getKind() != null ? obj.getKind() : "?");
                    }
                }
            }
        }
    }

    private void collectObjectIds(List<StoryboardObject> objects,
                                  Map<String, String> idToKind,
                                  Map<String, StoryboardObject> enrichedRegistry) {
        if (objects == null) {
            return;
        }
        for (StoryboardObject obj : objects) {
            if (obj == null || obj.getId() == null) {
                continue;
            }
            String kind = obj.getKind();
            if (!StringUtils.hasText(kind)) {
                StoryboardObject regObj = enrichedRegistry.get(obj.getId());
                kind = regObj != null && regObj.getKind() != null ? regObj.getKind() : "?";
            }
            idToKind.putIfAbsent(obj.getId(), kind);
        }
    }

    private String extractConstraintTargetId(StoryboardConstraint c) {
        if (c == null) {
            return null;
        }
        for (String ownerId : StoryboardConstraintUtils.ownerIds(c)) {
            if (StringUtils.hasText(ownerId)) {
                return ownerId;
            }
        }
        if (c.getRefs() == null) {
            return null;
        }
        for (String key : new String[]{"point", "object", "source", "label", "segment"}) {
            Object val = c.getRefs().get(key);
            if (val instanceof String && StringUtils.hasText((String) val)) {
                return (String) val;
            }
        }
        return null;
    }

    private String formatConstraintLine(String objId, String kind, StoryboardConstraint c) {
        if (c == null || !c.hasData()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(objId).append("(").append(kind).append("): ");
        sb.append(c.getDomain() != null ? c.getDomain() : "?").append("/");
        sb.append(c.getRelation() != null ? c.getRelation() : "?");
        if (StoryboardConstraintCatalog.isCoordinateDerivedRelation(c.getDomain(), c.getRelation())) {
            sb.append(" [coordinate-derived]");
        }
        if (StoryboardConstraintCatalog.isMotionSensitiveRelation(c.getDomain(), c.getRelation())) {
            sb.append(" [motion-sensitive]");
        }
        Set<String> ownerRoles = StoryboardConstraintCatalog.ownerRefRoles(c.getDomain(), c.getRelation());
        Set<String> dependencyRoles = StoryboardConstraintCatalog.dependencyRefRoles(c.getDomain(), c.getRelation());
        Set<String> ownerIds = StoryboardConstraintUtils.ownerIds(c);
        Set<String> dependencyIds = StoryboardConstraintUtils.dependencyIds(c);
        if (!ownerIds.isEmpty()) {
            sb.append(" owners=").append(ownerIds);
        }
        if (!dependencyIds.isEmpty()) {
            sb.append(" dependencies=").append(dependencyIds);
        }
        if (StringUtils.hasText(c.getStrength())) {
            sb.append(" strength=").append(c.getStrength().trim());
        }
        if (!ownerRoles.isEmpty()) {
            sb.append(" owner_roles=").append(ownerRoles);
        }
        if (!dependencyRoles.isEmpty()) {
            sb.append(" dependency_roles=").append(dependencyRoles);
        }
        if (c.getRefs() != null && !c.getRefs().isEmpty()) {
            sb.append(" refs=").append(c.getRefs());
        }
        if (c.getParameters() != null && !c.getParameters().isEmpty()) {
            sb.append(" params=").append(c.getParameters());
        }
        if (StringUtils.hasText(c.getReason())) {
            sb.append(" — ").append(c.getReason());
        }
        return sb.toString();
    }

    private String buildSingleSceneGenerationPrompt(Narrative narrative, String outputTarget) {
        if (narrative == null || !narrative.hasStoryboard()) {
            return "";
        }
        String basePrompt = NarrativePrompts.storyboardCodegenPrompt(
                narrative.getStoryboard(), outputTarget);
        boolean isGeoGebra = "geogebra".equalsIgnoreCase(outputTarget);
        String coordinateBoundsBlock = coordinateBoundsImplementationBlock(
                narrative.getStoryboard(), isGeoGebra, false);
        String artifactName = isGeoGebra
                ? GeoGebraCodeUtils.EXPECTED_FIGURE_NAME
                : ManimCodeUtils.EXPECTED_SCENE_NAME;
        String unwrapped = basePrompt.replaceFirst("^\\[CURRENT_REQUEST\\]\\n", "");
        if (isGeoGebra) {
            return SystemPrompts.buildCurrentRequestSection(unwrapped
                    + coordinateBoundsBlock
                    + "\n\nFigure name: " + artifactName
                    + "\nUse this as the primary GeoGebra figure name when naming the construction.");
        }
        return SystemPrompts.buildCurrentRequestSection(unwrapped
                + coordinateBoundsBlock
                + "\n\nScene class name: " + artifactName
                + "\nUse this exact scene class name verbatim in the generated code.");
    }

    private String buildRevisionRulesAppendix() {
        return SystemPrompts.buildRulesSection(
                "Complete-code user-revision mode. This section overrides only the per-scene response granularity in the code-generation rules above:\n"
                        + "- Keep all original code-generation, storyboard implementation, output-target, coordinate, object-lifecycle, and voiceover rules in force.\n"
                        + "- Treat the supplied complete existing generated code as the authoritative revision baseline for unrelated behavior.\n"
                        + "- Apply the user's instruction while preserving unrelated correct behavior.\n"
                        + "- For this call, do not return a scene method body or scene command block. Return the complete executable program for the whole approved storyboard.\n"
                        + "- Do not return a patch, diff, explanation, or partial placeholder.");
    }

    private String buildRevisionFixedContextAppendix(StageGenerationRequest<CodeResult> request) {
        StringBuilder sb = new StringBuilder("Operation mode: user_revision.\n");
        if (request.getBaseStageVersion() != null) {
            sb.append("Base code_generation stage version: ")
                    .append(request.getBaseStageVersion())
                    .append(".\n");
        }
        sb.append("The current request includes the complete approved storyboard context, the user instruction, and the complete existing generated code.\n");
        sb.append("Perform the revision in one model call and return the complete executable code through the whole-program code schema.\n");
        return SystemPrompts.buildFixedContextSection(sb.toString());
    }

    private String buildCompleteCodeRevisionPrompt(Narrative narrative,
                                                   String outputTarget,
                                                   StageGenerationRequest<CodeResult> request) {
        String existingCode = request.getExistingArtifact() != null
                ? request.getExistingArtifact().getGeneratedCode()
                : "";
        String storyboardContext = buildSingleSceneGenerationPrompt(narrative, outputTarget)
                .replaceFirst("^\\[CURRENT_REQUEST\\]\\n", "");
        String language = "geogebra".equalsIgnoreCase(outputTarget) ? "geogebra" : "python";
        String artifactType = "geogebra".equalsIgnoreCase(outputTarget)
                ? "GeoGebra command program"
                : "Python/Manim file";
        return SystemPrompts.buildCurrentRequestSection(
                "Revise the complete existing generated program in one pass.\n\n"
                        + "User revision instruction:\n" + request.getInstruction()
                        + "\n\nComplete existing generated code (authoritative revision baseline):\n```"
                        + language + "\n" + existingCode + "\n```\n\n"
                        + "Approved storyboard implementation context:\n"
                        + storyboardContext
                        + "\n\nApply only the requested correction and any directly necessary consistency fixes. "
                        + "Preserve unrelated correct code, object identity, scene order, narration, and lifecycle behavior.\n"
                        + "Return the complete executable " + artifactType
                        + " for all scenes, not a patch, diff, explanation, or individual scene fragment.");
    }

    private String buildCompactObjectRegistryJson(Storyboard storyboard) {
        if (storyboard == null || storyboard.getObjectRegistry() == null
                || storyboard.getObjectRegistry().isEmpty()) {
            return "";
        }
        ArrayNode array = objectMapper.createArrayNode();
        for (StoryboardObject object : storyboard.getObjectRegistry()) {
            if (object == null || !StringUtils.hasText(object.getId())) {
                continue;
            }
            ObjectNode node = array.addObject();
            node.put("id", object.getId());
            putNonBlank(node, "kind", object.getKind());
            putNonBlank(node, "content", object.getContent());
            if (object.getStyle() != null && object.getStyle().hasData()) {
                node.set("style", objectMapper.valueToTree(object.getStyle()));
            }
            if (object.getConstraints() != null && !object.getConstraints().isEmpty()) {
                node.set("constraints", objectMapper.valueToTree(object.getConstraints()));
            }
        }
        return array.isEmpty() ? "" : toPrettyJson(array);
    }

    private static void putNonBlank(ObjectNode node, String fieldName, String value) {
        if (StringUtils.hasText(value)) {
            node.put(fieldName, value.trim());
        }
    }

    // ==== small helpers ====

    private List<String> preferredCodeFields(String outputTarget) {
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            return List.of("geogebraCode");
        }
        return List.of("manimCode");
    }

    private String textField(JsonNode payload, String field) {
        if (payload == null || field == null) {
            return "";
        }
        JsonNode node = payload.get(field);
        return node != null && node.isTextual() && StringUtils.hasText(node.asText()) ? node.asText() : "";
    }

    private String firstTextField(JsonNode payload, String... fields) {
        if (fields == null) {
            return "";
        }
        for (String field : fields) {
            String value = textField(payload, field);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String normalizeCode(String code, String outputTarget) {
        String extracted = extractCodeBlock(code);
        if (!StringUtils.hasText(extracted)) {
            extracted = code;
        }
        if (!StringUtils.hasText(extracted)) {
            return "";
        }
        String normalized = extracted.replace("\r\n", "\n").replace('\r', '\n').trim();
        if ("geogebra".equalsIgnoreCase(outputTarget)) {
            normalized = GeoGebraCodeUtils.extractCode(normalized);
        } else {
            normalized = ManimCodeUtils.enforceMainSceneName(normalized);
        }
        return normalized;
    }

    private String extractCodeBlock(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        Matcher matcher = FENCED_CODE.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : text.trim();
    }

    private static String toBlock(String content) {
        return StringUtils.hasText(content) ? "\n\n" + content : "";
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON: " + e.getMessage(), e);
        }
    }

    private double secondsSince(Instant start) {
        return Duration.between(start, Instant.now()).toMillis() / 1000.0D;
    }

    private static final class SceneEntry {
        private final String name;
        private final String code;

        private SceneEntry(String name, String code) {
            this.name = name;
            this.code = code;
        }
    }

    private static final class SingleSceneDraft {
        private final String code;
        private final String artifactName;
        private final String description;

        private SingleSceneDraft(String code, String artifactName, String description) {
            this.code = code;
            this.artifactName = artifactName;
            this.description = description;
        }
    }

    private static final class PerSceneDraft {
        private final String generatedCode;
        private final String headerCode;
        private final List<SceneCodeEntry> sceneEntries;

        private PerSceneDraft(String generatedCode,
                              String headerCode,
                              List<SceneCodeEntry> sceneEntries) {
            this.generatedCode = generatedCode;
            this.headerCode = headerCode;
            this.sceneEntries = sceneEntries;
        }
    }

    public static final class Result {
        private final CodeResult codeResult;
        private final int apiCalls;

        private Result(CodeResult codeResult, int apiCalls) {
            this.codeResult = codeResult;
            this.apiCalls = apiCalls;
        }

        public CodeResult getCodeResult() {
            return codeResult;
        }

        public int getApiCalls() {
            return apiCalls;
        }
    }
}
