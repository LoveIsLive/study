package com.kwang.study.mathvision.engine.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionResult;
import com.kwang.study.mathvision.engine.MathVisionStageExecutor;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.MathVisionArtifact;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.pojo.MathVisionVersion;
import com.kwang.study.mathvision.workflow.model.KnowledgeGraph;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.StageGenerationMode;
import com.kwang.study.mathvision.workflow.model.StageGenerationRequest;
import com.kwang.study.mathvision.workflow.node.ExplorationNode;
import com.kwang.study.mathvision.workflow.node.MathEnrichmentNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReasoningGraphStageExecutor implements MathVisionStageExecutor {

    private final MathVisionArtifactMapper artifactMapper;
    private final MathVisionVersionMapper versionMapper;
    private final ObjectMapper objectMapper;
    private final ExplorationNode explorationNode;
    private final MathEnrichmentNode mathEnrichmentNode;

    public ReasoningGraphStageExecutor(MathVisionArtifactMapper artifactMapper,
                                       MathVisionVersionMapper versionMapper,
                                       ObjectMapper objectMapper,
                                       ExplorationNode explorationNode,
                                       MathEnrichmentNode mathEnrichmentNode) {
        this.artifactMapper = artifactMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
        this.explorationNode = explorationNode;
        this.mathEnrichmentNode = mathEnrichmentNode;
    }

    @Override
    public StageEnum stage() {
        return StageEnum.REASONING_GRAPH;
    }

    @Override
    public MathVisionStageExecutionResult execute(MathVisionStageExecutionContext context) {
        MathVisionTask task = context.getTask();
        ProblemBundle bundle = loadProblemBundle(task);
        bundle.setOutputTarget(task.getOutputTarget());

        StageGenerationRequest<KnowledgeGraph> revisionRequest = context.isUserRevision()
                ? StageGenerationRequest.<KnowledgeGraph>builder()
                        .mode(StageGenerationMode.USER_REVISION)
                        .existingArtifact(readExistingGraph(context))
                        .instruction(context.getInstruction())
                        .baseStageVersion(context.getBaseStageVersion())
                        .build()
                : null;
        ExplorationNode.Result explorationResult = context.isUserRevision()
                ? explorationNode.run(task, bundle, revisionRequest, context)
                : explorationNode.run(task, bundle, context);
        KnowledgeGraph graph = explorationResult.getGraph();
        com.fasterxml.jackson.databind.JsonNode explorationCheckpoint =
                objectMapper.valueToTree(graph).deepCopy();
        MathEnrichmentNode.Result enrichmentResult = context.isUserRevision()
                ? mathEnrichmentNode.run(task, bundle, graph, revisionRequest, context)
                : mathEnrichmentNode.run(task, bundle, graph, context);

        ObjectNode resultJson = objectMapper.createObjectNode();
        resultJson.put("apiCalls", explorationResult.getApiCalls() + enrichmentResult.getApiCalls());
        resultJson.put("resolvedInputMode", explorationResult.getResolvedInputMode());
        resultJson.put("nodeCount", graph.countNodes());
        resultJson.put("edgeCount", graph.countEdges());
        resultJson.put("maxDepth", graph.getMaxDepth());
        resultJson.put("enrichedCount", enrichmentResult.getEnrichedCount());
        resultJson.put("skippedCount", enrichmentResult.getSkippedCount());
        ObjectNode checkpoints = resultJson.putObject("internalCheckpoints");
        checkpoints.set("explorationGraph", explorationCheckpoint);
        checkpoints.set("enrichedGraph", objectMapper.valueToTree(graph));

        return MathVisionStageExecutionResult.builder()
                .artifactJson(toPrettyJson(graph))
                .resultJson(toPrettyJson(resultJson))
                .changeSource(context.isUserRevision() ? "user_revision" : "initial_generation")
                .changeSummary(context.isUserRevision()
                        ? "regenerate complete reasoning graph and math enrichment from user feedback"
                        : "complete reasoning graph and math enrichment")
                .build();
    }

    private KnowledgeGraph readExistingGraph(MathVisionStageExecutionContext context) {
        try {
            return objectMapper.readValue(context.getExistingArtifactJson(), KnowledgeGraph.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse user-revision reasoning graph: " + e.getMessage(), e);
        }
    }

    private ProblemBundle loadProblemBundle(MathVisionTask task) {
        MathVisionVersion version = currentVersion(task);
        if (version == null || version.getPnVersion() == null) {
            throw new IllegalStateException("Missing ProblemBundle artifact. Run problem_normalization first.");
        }
        MathVisionArtifact artifact = artifactMapper.findByTaskStageVersion(
                task.getId(), StageEnum.PROBLEM_NORMALIZATION.getCode(), version.getPnVersion());
        if (artifact == null || !StringUtils.hasText(artifact.getArtifactJson())) {
            throw new IllegalStateException("ProblemBundle artifact is empty. Rerun problem_normalization.");
        }
        try {
            return objectMapper.readValue(artifact.getArtifactJson(), ProblemBundle.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse ProblemBundle artifact: " + e.getMessage(), e);
        }
    }

    private MathVisionVersion currentVersion(MathVisionTask task) {
        MathVisionVersion version = versionMapper.findCurrent(task.getId());
        if (version == null && task.getCurrentVersion() != null) {
            version = versionMapper.findByTaskVersion(task.getId(), task.getCurrentVersion());
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
