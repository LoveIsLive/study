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
import com.kwang.study.mathvision.workflow.prompt.ExplorationPrompts;
import com.kwang.study.mathvision.workflow.prompt.SystemPrompts;
import com.kwang.study.mathvision.workflow.prompt.ToolSchemas;
import com.kwang.study.mathvision.workflow.util.ConceptUtils;
import com.kwang.study.mathvision.workflow.util.ProblemBundleContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ExplorationNode {

    private static final Logger log = LoggerFactory.getLogger(ExplorationNode.class);

    private final MathVisionAiChatService aiChatService;
    private final ObjectMapper objectMapper;

    public ExplorationNode(MathVisionAiChatService aiChatService,
                           ObjectMapper objectMapper) {
        this.aiChatService = aiChatService;
        this.objectMapper = objectMapper;
    }

    public Result run(MathVisionTask task, ProblemBundle bundle, MathVisionStageExecutionContext context) {
        return run(task, bundle, StageGenerationRequest.initialGeneration(), context);
    }

    public Result run(MathVisionTask task,
                      ProblemBundle bundle,
                      StageGenerationRequest<KnowledgeGraph> request,
                      MathVisionStageExecutionContext context) {
        StageGenerationRequest<KnowledgeGraph> resolvedRequest = request != null
                ? request
                : StageGenerationRequest.initialGeneration();
        validateRequest(resolvedRequest);
        bundle.setOutputTarget(task.getOutputTarget());
        Counter apiCalls = new Counter();
        context.checkCanceled();
        String resolvedMode = resolveInputMode(task, bundle, apiCalls);
        context.checkCanceled();

        KnowledgeGraph graph = buildGraph(task, bundle, resolvedMode, resolvedRequest, apiCalls);
        validateGraph(graph);
        return new Result(graph, resolvedMode, apiCalls.value);
    }

    private String resolveInputMode(MathVisionTask task, ProblemBundle bundle, Counter apiCalls) {
        String explicit = ProblemBundleContextBuilder.normalizeInputMode(bundle.getInputMode());
        if ("concept".equals(explicit) || "problem".equals(explicit)) {
            return explicit;
        }

        try {
            apiCalls.increment();
            String decision = aiChatService.requestText(
                    task,
                    List.of(
                            AiMessage.system(ExplorationPrompts.buildInputModeRulesPrompt()),
                            AiMessage.system(ExplorationPrompts.buildInputModeFixedContextPrompt()),
                            AiMessage.user(List.of(AiContentPart.text(SystemPrompts.buildCurrentRequestSection(
                                    ProblemBundleContextBuilder.buildProblemBundleAuthorityContext(bundle)
                                            + "\n\nClassify the routing mode for this workflow input."))))
                    ),
                    ToolSchemas.INPUT_MODE,
                    List.of("input_mode"));
            String normalized = decision == null ? "" : decision.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("concept")) {
                return "concept";
            }
            if (normalized.startsWith("problem")) {
                return "problem";
            }
            log.warn("MathVision input-mode classifier returned an unexpected value: {}", normalized);
        } catch (RuntimeException e) {
            log.warn("MathVision input-mode classification failed; using heuristic fallback: {}", e.getMessage());
        }
        return classifyInputModeHeuristically(displayTarget(bundle));
    }

    private String classifyInputModeHeuristically(String input) {
        String normalized = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        int wordCount = normalized.isBlank() ? 0 : normalized.split("\\s+").length;
        boolean looksLikeProblem = normalized.contains("?")
                || normalized.contains("problem")
                || normalized.contains("prove")
                || normalized.contains("show that")
                || normalized.contains("solve")
                || normalized.contains("find")
                || normalized.contains("determine")
                || normalized.contains("minimize")
                || normalized.contains("maximize")
                || normalized.contains("minimum")
                || normalized.contains("maximum")
                || normalized.contains("given")
                || normalized.contains("let ")
                || wordCount > 12;
        return looksLikeProblem ? "problem" : "concept";
    }

    private KnowledgeGraph buildGraph(MathVisionTask task,
                                      ProblemBundle bundle,
                                      String resolvedMode,
                                      StageGenerationRequest<KnowledgeGraph> request,
                                      Counter apiCalls) {
        boolean problemMode = "problem".equals(resolvedMode);
        String targetLabel = displayTarget(bundle);
        String targetDescription = buildGraphTargetDescription(task.getOutputTarget(), resolvedMode);
        String rulesPrompt = problemMode
                ? ExplorationPrompts.buildProblemGraphRulesPrompt()
                : ExplorationPrompts.buildConceptGraphRulesPrompt();
        String fixedContext = problemMode
                ? ExplorationPrompts.buildProblemGraphFixedContextPrompt(targetDescription)
                : ExplorationPrompts.buildConceptGraphFixedContextPrompt(targetDescription);
        StringBuilder currentRequestBody = new StringBuilder(
                ProblemBundleContextBuilder.buildProblemBundleAuthorityContext(bundle)
                        + "\n\nMath " + (problemMode ? "problem" : "concept") + " statement:\n"
                        + targetLabel + "\n\n"
                        + "Presentation target: " + task.getOutputTarget() + ".");
        if (request.isUserRevision()) {
            rulesPrompt += "\n\n" + buildRevisionRulesAppendix();
            fixedContext += "\n\n" + buildRevisionFixedContextAppendix(
                    request.getBaseStageVersion());
            currentRequestBody.append("\n\nExisting reasoning graph (revision baseline):\n```json\n")
                    .append(toPrettyJson(request.getExistingArtifact()))
                    .append("\n```\n\nUser revision instruction:\n")
                    .append(request.getInstruction().trim())
                    .append("\n\nRegenerate the complete reasoning graph through the normal output contract.");
        }
        String currentRequest = SystemPrompts.buildCurrentRequestSection(currentRequestBody.toString());

        JsonNode payload = aiChatService.requestJson(
                task,
                List.of(
                        AiMessage.system(rulesPrompt),
                        AiMessage.system(fixedContext),
                        AiMessage.user(List.of(AiContentPart.text(currentRequest)))
                ),
                problemMode ? ToolSchemas.PROBLEM_GRAPH : ToolSchemas.CONCEPT_GRAPH);
        apiCalls.increment();
        return parseGraphPayload(payload, problemMode ? DirectGraphMode.PROBLEM : DirectGraphMode.CONCEPT);
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

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON: " + e.getMessage(), e);
        }
    }

    private String buildRevisionRulesAppendix() {
        return SystemPrompts.buildRulesSection(
                "Reasoning-graph user-revision mode:\n"
                        + "- Treat the supplied graph as the revision baseline and the ProblemBundle as source authority.\n"
                        + "- Apply the user instruction while preserving unrelated correct nodes, teaching order, and graph continuity.\n"
                        + "- Return the complete revised graph through the normal graph tool contract.\n"
                        + "- Do not return a patch, diff, explanation, or partial graph.");
    }

    private String buildRevisionFixedContextAppendix(Integer baseStageVersion) {
        StringBuilder sb = new StringBuilder("Operation mode: user_revision.\n");
        if (baseStageVersion != null) {
            sb.append("Base reasoning_graph stage version: ").append(baseStageVersion).append(".\n");
        }
        sb.append("The current request contains the existing reasoning graph and the user's revision instruction.\n");
        return SystemPrompts.buildFixedContextSection(sb.toString());
    }

    private KnowledgeGraph parseGraphPayload(JsonNode payload, DirectGraphMode graphMode) {
        Map<String, KnowledgeNode> nodes = parseGraphNodes(payload, graphMode);
        Map<String, List<String>> nextEdges = parseNextEdges(payload, nodes);
        String requestedStartId = payload != null && payload.hasNonNull("start_id")
                ? ConceptUtils.normalizeConcept(payload.get("start_id").asText())
                : "";
        String startId = selectStart(requestedStartId, nodes, nextEdges, graphMode);
        recomputeDepths(startId, nodes, nextEdges);
        List<String> teachingOrder = parseTeachingOrder(payload, nodes);
        return new KnowledgeGraph(startId, orderNodes(nodes), orderEdges(nextEdges, nodes), teachingOrder);
    }

    private Map<String, KnowledgeNode> parseGraphNodes(JsonNode payload, DirectGraphMode graphMode) {
        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        JsonNode nodesArray = payload != null ? payload.get("nodes") : null;
        if (nodesArray == null || !nodesArray.isArray()) {
            return nodes;
        }

        for (JsonNode nodeJson : nodesArray) {
            String rawId = nodeJson.hasNonNull("id") ? nodeJson.get("id").asText() : nodeJson.path("step").asText("");
            String nodeId = ConceptUtils.normalizeConcept(rawId);
            if (!StringUtils.hasText(nodeId) || nodes.containsKey(nodeId)) {
                continue;
            }
            String step = nodeJson.path("step").asText("").trim();
            if (!StringUtils.hasText(step)) {
                step = rawId == null ? "" : rawId.trim();
            }
            if (!StringUtils.hasText(step)) {
                continue;
            }
            int depth = nodeJson.has("min_depth") ? nodeJson.get("min_depth").asInt(0) : 0;
            KnowledgeNode node = new KnowledgeNode(nodeId, step, depth);
            node.setNodeType(sanitizeNodeType(nodeJson.path("node_type").asText(""), graphMode));
            String reason = nodeJson.path("reason").asText("").trim();
            if (StringUtils.hasText(reason)) {
                node.setReason(reason);
            }
            nodes.put(nodeId, node);
        }
        return nodes;
    }

    private Map<String, List<String>> parseNextEdges(JsonNode payload, Map<String, KnowledgeNode> nodes) {
        Map<String, List<String>> nextEdges = new LinkedHashMap<>();
        JsonNode edgeObject = payload != null ? payload.get("next_edges") : null;
        if (edgeObject == null || !edgeObject.isObject()) {
            return nextEdges;
        }

        edgeObject.fields().forEachRemaining(entry -> {
            String sourceId = ConceptUtils.normalizeConcept(entry.getKey());
            if (!StringUtils.hasText(sourceId) || !nodes.containsKey(sourceId)) {
                return;
            }
            List<String> nextNodeIds = new ArrayList<>();
            JsonNode nextNodeArray = entry.getValue();
            if (nextNodeArray != null && nextNodeArray.isArray()) {
                for (JsonNode nextNodeJson : nextNodeArray) {
                    String nextNodeId = ConceptUtils.normalizeConcept(nextNodeJson.asText());
                    if (!StringUtils.hasText(nextNodeId)
                            || nextNodeId.equals(sourceId)
                            || !nodes.containsKey(nextNodeId)
                            || nextNodeIds.contains(nextNodeId)) {
                        continue;
                    }
                    nextNodeIds.add(nextNodeId);
                }
            }
            if (!nextNodeIds.isEmpty()) {
                nextEdges.put(sourceId, nextNodeIds);
            }
        });
        return nextEdges;
    }

    private List<String> parseTeachingOrder(JsonNode payload, Map<String, KnowledgeNode> nodes) {
        List<String> order = new ArrayList<>();
        JsonNode orderArray = payload != null ? payload.get("teaching_order") : null;
        if (orderArray != null && orderArray.isArray()) {
            for (JsonNode item : orderArray) {
                String nodeId = ConceptUtils.normalizeConcept(item.asText());
                if (StringUtils.hasText(nodeId) && nodes.containsKey(nodeId) && !order.contains(nodeId)) {
                    order.add(nodeId);
                }
            }
        }
        for (String nodeId : nodes.keySet()) {
            if (!order.contains(nodeId)) {
                order.add(nodeId);
            }
        }
        return order;
    }

    private String sanitizeNodeType(String rawNodeType, DirectGraphMode graphMode) {
        String normalized = rawNodeType == null ? "" : rawNodeType.trim().toLowerCase(Locale.ROOT);
        return graphMode.allowedNodeTypes.contains(normalized) ? normalized : graphMode.defaultNodeType;
    }

    private String selectStart(String requestedStartId,
                               Map<String, KnowledgeNode> nodes,
                               Map<String, List<String>> nextEdges,
                               DirectGraphMode graphMode) {
        if (nodes.isEmpty()) {
            throw new IllegalStateException("AI returned an empty knowledge graph.");
        }
        Map<String, Integer> indegree = computeIndegree(nodes, nextEdges);
        List<String> startCandidates = new ArrayList<>();
        for (String nodeId : nodes.keySet()) {
            if (indegree.getOrDefault(nodeId, 0) == 0) {
                startCandidates.add(nodeId);
            }
        }
        String normalizedRequestedStartId = ConceptUtils.normalizeConcept(requestedStartId);
        if (startCandidates.contains(normalizedRequestedStartId)) {
            return normalizedRequestedStartId;
        }
        if (!startCandidates.isEmpty()) {
            startCandidates.sort(startComparator(nodes, graphMode));
            return startCandidates.get(0);
        }
        return createSyntheticStart(nodes, nextEdges, graphMode);
    }

    private Map<String, Integer> computeIndegree(Map<String, KnowledgeNode> nodes, Map<String, List<String>> nextEdges) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (String nodeId : nodes.keySet()) {
            indegree.put(nodeId, 0);
        }
        for (List<String> nextNodeIds : nextEdges.values()) {
            for (String nextNodeId : nextNodeIds) {
                indegree.computeIfPresent(nextNodeId, (ignored, count) -> count + 1);
            }
        }
        return indegree;
    }

    private Comparator<String> startComparator(Map<String, KnowledgeNode> nodes, DirectGraphMode graphMode) {
        return Comparator.comparingInt((String id) -> {
                    KnowledgeNode node = nodes.get(id);
                    return startTypeRank(node != null ? node.getNodeType() : "", graphMode);
                })
                .thenComparingInt(id -> {
                    KnowledgeNode node = nodes.get(id);
                    return node != null ? node.getMinDepth() : Integer.MAX_VALUE;
                })
                .thenComparing(id -> {
                    KnowledgeNode node = nodes.get(id);
                    return node != null ? node.getStep() : id;
                }, String.CASE_INSENSITIVE_ORDER);
    }

    private int startTypeRank(String nodeType, DirectGraphMode graphMode) {
        String normalized = nodeType == null ? "" : nodeType.trim().toLowerCase(Locale.ROOT);
        if (DirectGraphMode.CONCEPT == graphMode && KnowledgeNode.NODE_TYPE_CONCEPT.equals(normalized)) {
            return 0;
        }
        if (DirectGraphMode.PROBLEM == graphMode && KnowledgeNode.NODE_TYPE_PROBLEM.equals(normalized)) {
            return 0;
        }
        if (KnowledgeNode.NODE_TYPE_OBSERVATION.equals(normalized)) {
            return 1;
        }
        if (KnowledgeNode.NODE_TYPE_CONSTRUCTION.equals(normalized)) {
            return 2;
        }
        if (KnowledgeNode.NODE_TYPE_DERIVATION.equals(normalized)) {
            return 3;
        }
        if (KnowledgeNode.NODE_TYPE_CONCLUSION.equals(normalized)) {
            return 4;
        }
        return 5;
    }

    private String createSyntheticStart(Map<String, KnowledgeNode> nodes,
                                        Map<String, List<String>> nextEdges,
                                        DirectGraphMode graphMode) {
        String candidate = graphMode.syntheticStartBaseId;
        int suffix = 2;
        while (nodes.containsKey(candidate)) {
            candidate = graphMode.syntheticStartBaseId + "_" + suffix++;
        }
        KnowledgeNode startNode = new KnowledgeNode(candidate, graphMode.syntheticStartStep, 0);
        startNode.setNodeType(graphMode.syntheticStartNodeType);
        startNode.setReason(graphMode.syntheticStartReason);
        nodes.put(candidate, startNode);

        Map<String, Integer> indegree = computeIndegree(nodes, nextEdges);
        List<String> fallbackStartCandidates = new ArrayList<>();
        for (String nodeId : nodes.keySet()) {
            if (!candidate.equals(nodeId) && indegree.getOrDefault(nodeId, 0) == 0) {
                fallbackStartCandidates.add(nodeId);
            }
        }
        if (fallbackStartCandidates.isEmpty()) {
            fallbackStartCandidates.addAll(nodes.keySet());
            fallbackStartCandidates.remove(candidate);
        }
        if (!fallbackStartCandidates.isEmpty()) {
            fallbackStartCandidates.sort(startComparator(nodes, graphMode));
            nextEdges.put(candidate, fallbackStartCandidates);
        }
        return candidate;
    }

    private void recomputeDepths(String startId,
                                 Map<String, KnowledgeNode> nodes,
                                 Map<String, List<String>> nextEdges) {
        Map<String, Integer> computedDepths = new LinkedHashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        if (nodes.containsKey(startId)) {
            computedDepths.put(startId, 0);
            queue.add(startId);
        }
        int nextComponentDepth = traverse(queue, computedDepths, nextEdges);

        List<String> remaining = new ArrayList<>(nodes.keySet());
        remaining.sort(Comparator.comparingInt((String id) -> nodes.get(id).getMinDepth())
                .thenComparing(id -> nodes.get(id).getStep(), String.CASE_INSENSITIVE_ORDER));
        for (String nodeId : remaining) {
            if (computedDepths.containsKey(nodeId)) {
                continue;
            }
            computedDepths.put(nodeId, nextComponentDepth);
            queue.add(nodeId);
            nextComponentDepth = traverse(queue, computedDepths, nextEdges);
        }
        for (KnowledgeNode node : nodes.values()) {
            Integer depth = computedDepths.get(node.getId());
            node.setMinDepth(depth != null ? depth : 0);
        }
    }

    private int traverse(ArrayDeque<String> queue,
                         Map<String, Integer> computedDepths,
                         Map<String, List<String>> nextEdges) {
        int maxSeenDepth = computedDepths.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        while (!queue.isEmpty()) {
            String currentId = queue.removeFirst();
            int currentDepth = computedDepths.getOrDefault(currentId, 0);
            maxSeenDepth = Math.max(maxSeenDepth, currentDepth);
            for (String nextNodeId : nextEdges.getOrDefault(currentId, Collections.emptyList())) {
                int candidateDepth = currentDepth + 1;
                Integer existingDepth = computedDepths.get(nextNodeId);
                if (existingDepth == null || candidateDepth < existingDepth) {
                    computedDepths.put(nextNodeId, candidateDepth);
                    queue.addLast(nextNodeId);
                    maxSeenDepth = Math.max(maxSeenDepth, candidateDepth);
                }
            }
        }
        return maxSeenDepth + 1;
    }

    private Map<String, KnowledgeNode> orderNodes(Map<String, KnowledgeNode> nodeIndex) {
        List<KnowledgeNode> nodes = new ArrayList<>(nodeIndex.values());
        nodes.sort(Comparator.comparingInt(KnowledgeNode::getMinDepth)
                .thenComparing(KnowledgeNode::getStep, String.CASE_INSENSITIVE_ORDER));
        Map<String, KnowledgeNode> ordered = new LinkedHashMap<>();
        for (KnowledgeNode node : nodes) {
            ordered.put(node.getId(), node);
        }
        return ordered;
    }

    private Map<String, List<String>> orderEdges(Map<String, List<String>> edgeIndex,
                                                 Map<String, KnowledgeNode> nodes) {
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        List<String> sourceIds = new ArrayList<>(edgeIndex.keySet());
        sourceIds.sort(Comparator.comparingInt((String id) -> nodes.get(id).getMinDepth())
                .thenComparing(id -> nodes.get(id).getStep(), String.CASE_INSENSITIVE_ORDER));
        for (String sourceId : sourceIds) {
            List<String> nextNodeIds = new ArrayList<>(edgeIndex.getOrDefault(sourceId, Collections.emptyList()));
            nextNodeIds.sort(Comparator.comparingInt((String id) -> nodes.get(id).getMinDepth())
                    .thenComparing(id -> nodes.get(id).getStep(), String.CASE_INSENSITIVE_ORDER));
            ordered.put(sourceId, nextNodeIds);
        }
        return ordered;
    }

    private void validateGraph(KnowledgeGraph graph) {
        if (graph == null || graph.countNodes() == 0) {
            throw new IllegalStateException("AI returned no usable knowledge graph.");
        }
    }

    private String buildGraphTargetDescription(String outputTarget, String resolvedMode) {
        boolean geogebra = "geogebra".equalsIgnoreCase(outputTarget);
        String medium = geogebra ? "interactive geometry construction" : "teaching animation";
        if ("problem".equals(resolvedMode)) {
            return "Solve the user-provided math problem through a coherent "
                    + medium
                    + " that first makes the problem's meaning visible through motion or manipulation when feasible, then explains the reasoning and conclusion clearly.";
        }
        return "Explain the user-provided math concept through a coherent "
                + medium
                + " that first makes the concept's meaning visible through motion or manipulation when feasible, then builds a clear, learner-facing teaching flow.";
    }

    private String displayTarget(ProblemBundle bundle) {
        if (bundle == null) {
            return "";
        }
        return firstNonBlank(bundle.getStatement(), bundle.getTitle(), bundle.getId());
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    public static final class Result {
        private final KnowledgeGraph graph;
        private final String resolvedInputMode;
        private final int apiCalls;

        private Result(KnowledgeGraph graph, String resolvedInputMode, int apiCalls) {
            this.graph = graph;
            this.resolvedInputMode = resolvedInputMode;
            this.apiCalls = apiCalls;
        }

        public KnowledgeGraph getGraph() {
            return graph;
        }

        public String getResolvedInputMode() {
            return resolvedInputMode;
        }

        public int getApiCalls() {
            return apiCalls;
        }
    }

    private enum DirectGraphMode {
        CONCEPT(
                Set.of(
                        KnowledgeNode.NODE_TYPE_CONCEPT,
                        KnowledgeNode.NODE_TYPE_OBSERVATION,
                        KnowledgeNode.NODE_TYPE_CONSTRUCTION,
                        KnowledgeNode.NODE_TYPE_DERIVATION,
                        KnowledgeNode.NODE_TYPE_CONCLUSION
                ),
                KnowledgeNode.NODE_TYPE_CONCEPT,
                "concept_entry",
                "引入概念并呈现第一个关键观察",
                "该步骤用于界定概念，并建立学习者理解所需的第一个直观支点。",
                KnowledgeNode.NODE_TYPE_CONCEPT
        ),
        PROBLEM(
                Set.of(
                        KnowledgeNode.NODE_TYPE_PROBLEM,
                        KnowledgeNode.NODE_TYPE_OBSERVATION,
                        KnowledgeNode.NODE_TYPE_CONSTRUCTION,
                        KnowledgeNode.NODE_TYPE_DERIVATION,
                        KnowledgeNode.NODE_TYPE_CONCLUSION
                ),
                KnowledgeNode.NODE_TYPE_DERIVATION,
                "problem_entry",
                "呈现问题并引出第一步解题思路",
                "该步骤用于明确问题情境，并建立后续求解的起点。",
                KnowledgeNode.NODE_TYPE_PROBLEM
        );

        private final Set<String> allowedNodeTypes;
        private final String defaultNodeType;
        private final String syntheticStartBaseId;
        private final String syntheticStartStep;
        private final String syntheticStartReason;
        private final String syntheticStartNodeType;

        DirectGraphMode(Set<String> allowedNodeTypes,
                        String defaultNodeType,
                        String syntheticStartBaseId,
                        String syntheticStartStep,
                        String syntheticStartReason,
                        String syntheticStartNodeType) {
            this.allowedNodeTypes = allowedNodeTypes;
            this.defaultNodeType = defaultNodeType;
            this.syntheticStartBaseId = syntheticStartBaseId;
            this.syntheticStartStep = syntheticStartStep;
            this.syntheticStartReason = syntheticStartReason;
            this.syntheticStartNodeType = syntheticStartNodeType;
        }
    }

    private static final class Counter {
        private int value;

        private void increment() {
            value++;
        }
    }

}
