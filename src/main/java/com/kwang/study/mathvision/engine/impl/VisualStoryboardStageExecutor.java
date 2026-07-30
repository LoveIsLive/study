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
import com.kwang.study.mathvision.workflow.model.Narrative;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.VisualDesignMode;
import com.kwang.study.mathvision.workflow.model.VisualDesignRequest;
import com.kwang.study.mathvision.workflow.node.VisualDesignNode;
import com.kwang.study.mathvision.workflow.validation.StoryboardValidationNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class VisualStoryboardStageExecutor implements MathVisionStageExecutor {

    private final MathVisionArtifactMapper artifactMapper;
    private final MathVisionVersionMapper versionMapper;
    private final ObjectMapper objectMapper;
    private final VisualDesignNode visualDesignNode;
    private final StoryboardValidationNode storyboardValidationNode;

    public VisualStoryboardStageExecutor(MathVisionArtifactMapper artifactMapper,
                                         MathVisionVersionMapper versionMapper,
                                         ObjectMapper objectMapper,
                                         VisualDesignNode visualDesignNode,
                                         StoryboardValidationNode storyboardValidationNode) {
        this.artifactMapper = artifactMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
        this.visualDesignNode = visualDesignNode;
        this.storyboardValidationNode = storyboardValidationNode;
    }

    @Override
    public StageEnum stage() {
        return StageEnum.VISUAL_STORYBOARD;
    }

    @Override
    public MathVisionStageExecutionResult execute(MathVisionStageExecutionContext context) {
        MathVisionTask task = context.getTask();
        ProblemBundle bundle = loadProblemBundle(task);
        bundle.setOutputTarget(task.getOutputTarget());
        KnowledgeGraph graph = loadKnowledgeGraph(task);

        VisualDesignNode.Result designResult = context.isUserRevision()
                ? visualDesignNode.run(
                        task,
                        bundle,
                        graph,
                        VisualDesignRequest.builder()
                                .mode(VisualDesignMode.USER_REVISION)
                                .existingNarrative(readExistingNarrative(context))
                                .instruction(context.getInstruction())
                                .baseStageVersion(context.getBaseStageVersion())
                                .build(),
                        context)
                : visualDesignNode.run(task, bundle, graph, context);
        Narrative narrative = designResult.getNarrative();
        com.fasterxml.jackson.databind.JsonNode visualDesignCheckpoint =
                objectMapper.valueToTree(narrative).deepCopy();
        StoryboardValidationNode.Result validationResult =
                storyboardValidationNode.run(task, bundle, graph, narrative, context);
        Narrative validatedNarrative = validationResult.getNarrative() != null
                ? validationResult.getNarrative()
                : narrative;

        ObjectNode resultJson = objectMapper.createObjectNode();
        resultJson.put("apiCalls", designResult.getApiCalls() + validationResult.getApiCalls());
        resultJson.put("sceneCount", validatedNarrative.getStoryboard().getScenes().size());
        resultJson.put("objectCount", validatedNarrative.getStoryboard().getObjectRegistry().size());
        resultJson.put("validationCompleted", true);
        resultJson.put("sceneMode", designResult.getSceneMode());
        resultJson.set("validationReport", objectMapper.valueToTree(validationResult.getReport()));
        ObjectNode checkpoints = resultJson.putObject("internalCheckpoints");
        checkpoints.set("visualDesign", visualDesignCheckpoint);
        checkpoints.set("validatedStoryboard", objectMapper.valueToTree(validatedNarrative));

        return MathVisionStageExecutionResult.builder()
                .artifactJson(toPrettyJson(validatedNarrative))
                .resultJson(toPrettyJson(resultJson))
                .changeSource(context.isUserRevision() ? "user_revision" : "initial_generation")
                .changeSummary(context.isUserRevision()
                        ? "regenerate complete visual storyboard design and validation from user feedback"
                        : "complete visual storyboard design and validation")
                .build();
    }

    private Narrative readExistingNarrative(MathVisionStageExecutionContext context) {
        try {
            return objectMapper.readValue(context.getExistingArtifactJson(), Narrative.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse user-revision storyboard: " + e.getMessage(), e);
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

    private KnowledgeGraph loadKnowledgeGraph(MathVisionTask task) {
        MathVisionVersion version = currentVersion(task);
        if (version == null || version.getRgVersion() == null) {
            throw new IllegalStateException("Missing reasoning_graph artifact. Run reasoning_graph first.");
        }
        MathVisionArtifact artifact = artifactMapper.findByTaskStageVersion(
                task.getId(), StageEnum.REASONING_GRAPH.getCode(), version.getRgVersion());
        if (artifact == null || !StringUtils.hasText(artifact.getArtifactJson())) {
            throw new IllegalStateException("reasoning_graph artifact is empty. Rerun reasoning_graph.");
        }
        try {
            return objectMapper.readValue(artifact.getArtifactJson(), KnowledgeGraph.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse reasoning_graph artifact: " + e.getMessage(), e);
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
