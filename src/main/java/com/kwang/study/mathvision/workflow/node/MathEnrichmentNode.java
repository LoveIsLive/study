package com.kwang.study.mathvision.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.ai.MathVisionAiChatService;
import com.kwang.study.mathvision.workflow.model.AiContentPart;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.model.KnowledgeGraph;
import com.kwang.study.mathvision.workflow.model.KnowledgeNode;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.StageGenerationRequest;
import com.kwang.study.mathvision.workflow.prompt.EnrichmentPrompts;
import com.kwang.study.mathvision.workflow.prompt.SystemPrompts;
import com.kwang.study.mathvision.workflow.prompt.ToolSchemas;
import com.kwang.study.mathvision.workflow.util.TargetDescriptionBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MathEnrichmentNode {

    private static final int ROLLING_CONTEXT_ROUNDS = 10;

    private final MathVisionAiChatService aiChatService;
    private final ObjectMapper objectMapper;

    public MathEnrichmentNode(MathVisionAiChatService aiChatService, ObjectMapper objectMapper) {
        this.aiChatService = aiChatService;
        this.objectMapper = objectMapper;
    }

    public Result run(MathVisionTask task,
                      ProblemBundle bundle,
                      KnowledgeGraph graph,
                      MathVisionStageExecutionContext context) {
        return run(task, bundle, graph, StageGenerationRequest.initialGeneration(), context);
    }

    public Result run(MathVisionTask task,
                      ProblemBundle bundle,
                      KnowledgeGraph graph,
                      StageGenerationRequest<KnowledgeGraph> request,
                      MathVisionStageExecutionContext context) {
        StageGenerationRequest<KnowledgeGraph> resolvedRequest = request != null
                ? request
                : StageGenerationRequest.initialGeneration();
        validateRequest(resolvedRequest);
        if (graph == null) {
            return new Result(0, 0, 0);
        }

        int apiCalls = 0;
        int enrichedCount = 0;
        int skippedCount = 0;
        List<RollingTurn> rollingTurns = new ArrayList<>();
        String rulesPrompt = EnrichmentPrompts.buildRulesPrompt();
        String fixedContext = EnrichmentPrompts.buildFixedContextPrompt(
                bundle,
                TargetDescriptionBuilder.build(bundle, graph, null),
                TargetDescriptionBuilder.buildSolutionChain(graph, null));
        if (resolvedRequest.isUserRevision()) {
            rulesPrompt += "\n\n" + buildRevisionRulesAppendix();
            fixedContext += "\n\n" + buildRevisionFixedContextAppendix(
                    resolvedRequest.getBaseStageVersion());
        }

        List<KnowledgeNode> teachingNodes = graph.teachingOrderNodes();
        for (int i = 0; i < teachingNodes.size(); i++) {
            KnowledgeNode node = teachingNodes.get(i);
            context.checkCanceled();
            if (node == null) {
                skippedCount++;
                continue;
            }
            String userPrompt = resolvedRequest.isUserRevision()
                    ? buildRevisionCurrentStepPrompt(node, baselineNode(resolvedRequest.getExistingArtifact(), node, i), resolvedRequest)
                    : buildCurrentStepPrompt(node);
            List<AiMessage> messages = new ArrayList<>();
            messages.add(AiMessage.system(rulesPrompt));
            messages.add(AiMessage.system(fixedContext));
            for (RollingTurn turn : rollingTurns) {
                messages.add(AiMessage.user(List.of(AiContentPart.text(turn.userPrompt))));
                messages.add(new AiMessage("assistant", List.of(AiContentPart.text(turn.assistantText))));
            }
            messages.add(AiMessage.user(List.of(AiContentPart.text(userPrompt))));

            try {
                JsonNode payload = aiChatService.requestJson(task, messages, ToolSchemas.MATH_ENRICHMENT);
                apiCalls++;
                applyEnrichment(node, payload);
                appendRollingTurn(rollingTurns, userPrompt, toPrettyJson(payload));
                enrichedCount++;
            } catch (RuntimeException e) {
                skippedCount++;
            }
            context.checkCanceled();
        }
        return new Result(apiCalls, enrichedCount, skippedCount);
    }

    private void validateRequest(StageGenerationRequest<KnowledgeGraph> request) {
        if (!request.isUserRevision()) {
            return;
        }
        if (!StringUtils.hasText(request.getInstruction())) {
            throw new IllegalArgumentException("User revision instruction cannot be empty.");
        }
        if (request.getExistingArtifact() == null || request.getExistingArtifact().countNodes() == 0) {
            throw new IllegalArgumentException("Existing reasoning graph is required for user revision.");
        }
    }

    private KnowledgeNode baselineNode(KnowledgeGraph existingGraph, KnowledgeNode node, int index) {
        if (existingGraph == null) {
            return null;
        }
        KnowledgeNode byId = node != null ? existingGraph.getNode(node.getId()) : null;
        if (byId != null) {
            return byId;
        }
        List<KnowledgeNode> existingNodes = existingGraph.teachingOrderNodes();
        return index >= 0 && index < existingNodes.size() ? existingNodes.get(index) : null;
    }

    private String buildCurrentStepPrompt(KnowledgeNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("[CURRENT_STEP]\n");
        sb.append("- step: ").append(node.getStep()).append("\n");
        sb.append("- node_role: ").append(node.getNodeType() != null ? node.getNodeType() : "concept").append("\n");
        sb.append("[RESPONSE_SCOPE]\n");
        sb.append("Return only the mathematical content needed for this step.\n");
        sb.append("Do not restate the whole solution.\n");
        sb.append("Keep it concise and presentation-oriented.");
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }

    private String buildRevisionCurrentStepPrompt(KnowledgeNode node,
                                                  KnowledgeNode existingNode,
                                                  StageGenerationRequest<KnowledgeGraph> request) {
        StringBuilder sb = new StringBuilder();
        sb.append("[CURRENT_REGENERATED_STEP]\n");
        sb.append("- id: ").append(node.getId()).append("\n");
        sb.append("- step: ").append(node.getStep()).append("\n");
        sb.append("- node_role: ").append(node.getNodeType() != null ? node.getNodeType() : "concept").append("\n\n");
        sb.append("Existing enriched node (revision baseline):\n```json\n")
                .append(toPrettyJson(existingNode != null ? existingNode : Map.of()))
                .append("\n```\n\nUser revision instruction:\n")
                .append(request.getInstruction().trim())
                .append("\n\n[RESPONSE_SCOPE]\n")
                .append("Return the complete mathematical enrichment for this regenerated step.\n")
                .append("Preserve unrelated correct enrichment and do not restate the whole solution.");
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }

    private String buildRevisionRulesAppendix() {
        return SystemPrompts.buildRulesSection(
                "Mathematical-enrichment user-revision mode:\n"
                        + "- Treat each supplied enriched node as the revision baseline and the ProblemBundle as source authority.\n"
                        + "- Apply the user instruction while preserving unrelated correct equations, definitions, examples, and interpretations.\n"
                        + "- Return the complete enrichment for the current node through the normal tool contract.\n"
                        + "- Do not return a patch, diff, review report, or explanation.");
    }

    private String buildRevisionFixedContextAppendix(Integer baseStageVersion) {
        StringBuilder sb = new StringBuilder("Operation mode: user_revision.\n");
        if (baseStageVersion != null) {
            sb.append("Base reasoning_graph stage version: ").append(baseStageVersion).append(".\n");
        }
        sb.append("Each current request contains the existing enriched node and the user's revision instruction.\n");
        return SystemPrompts.buildFixedContextSection(sb.toString());
    }

    private void applyEnrichment(KnowledgeNode node, JsonNode data) {
        if (node == null || data == null || data.isNull()) {
            return;
        }
        if (data.has("step")) {
            String correctedStep = readOptionalText(data.get("step"));
            if (StringUtils.hasText(correctedStep)) {
                node.setStep(correctedStep);
            }
        }
        if (data.has("reason")) {
            String correctedReason = readOptionalText(data.get("reason"));
            if (StringUtils.hasText(correctedReason)) {
                node.setReason(correctedReason);
            }
        }
        if (data.has("equations")) {
            node.setEquations(readTrimmedStringList(data.get("equations")));
        }
        if (data.has("definitions")) {
            node.setDefinitions(readTrimmedStringMap(data.get("definitions")));
        }
        if (data.has("interpretation")) {
            node.setInterpretation(readOptionalText(data.get("interpretation")));
        }
        if (data.has("examples")) {
            node.setExamples(readTrimmedStringList(data.get("examples")));
        }
    }

    private List<String> readTrimmedStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String text = readOptionalText(item);
                if (text != null) {
                    values.add(text);
                }
            }
            return values;
        }
        String singleValue = readOptionalText(node);
        if (singleValue != null) {
            values.add(singleValue);
        }
        return values;
    }

    private Map<String, String> readTrimmedStringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node == null || node.isNull() || !node.isObject()) {
            return values;
        }
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey() == null ? null : entry.getKey().trim();
            String value = readOptionalText(entry.getValue());
            if (StringUtils.hasText(key) && value != null) {
                values.put(key, value);
            }
        });
        return values;
    }

    private String readOptionalText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void appendRollingTurn(List<RollingTurn> rollingTurns, String userPrompt, String assistantText) {
        rollingTurns.add(new RollingTurn(userPrompt, assistantText));
        while (rollingTurns.size() > ROLLING_CONTEXT_ROUNDS) {
            rollingTurns.remove(0);
        }
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON: " + e.getMessage(), e);
        }
    }

    public static final class Result {
        private final int apiCalls;
        private final int enrichedCount;
        private final int skippedCount;

        private Result(int apiCalls, int enrichedCount, int skippedCount) {
            this.apiCalls = apiCalls;
            this.enrichedCount = enrichedCount;
            this.skippedCount = skippedCount;
        }

        public int getApiCalls() {
            return apiCalls;
        }

        public int getEnrichedCount() {
            return enrichedCount;
        }

        public int getSkippedCount() {
            return skippedCount;
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
}
