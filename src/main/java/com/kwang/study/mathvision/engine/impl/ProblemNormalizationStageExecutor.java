package com.kwang.study.mathvision.engine.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionResult;
import com.kwang.study.mathvision.engine.MathVisionStageExecutor;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.StageGenerationMode;
import com.kwang.study.mathvision.workflow.model.StageGenerationRequest;
import com.kwang.study.mathvision.workflow.node.ProblemNormalizationNode;
import com.kwang.study.mathvision.workflow.util.NodeExecutionLogger;
import org.springframework.stereotype.Component;

@Component
public class ProblemNormalizationStageExecutor implements MathVisionStageExecutor {

    private final ObjectMapper objectMapper;
    private final ProblemNormalizationNode problemNormalizationNode;

    public ProblemNormalizationStageExecutor(ObjectMapper objectMapper,
                                             ProblemNormalizationNode problemNormalizationNode) {
        this.objectMapper = objectMapper;
        this.problemNormalizationNode = problemNormalizationNode;
    }

    @Override
    public StageEnum stage() {
        return StageEnum.PROBLEM_NORMALIZATION;
    }

    @Override
    public MathVisionStageExecutionResult execute(MathVisionStageExecutionContext context) {
        MathVisionTask task = context.getTask();
        ProblemNormalizationNode.Result nodeResult = NodeExecutionLogger.execute(
                task.getId(),
                stage().getCode(),
                "ProblemNormalizationNode",
                () -> context.isUserRevision()
                        ? problemNormalizationNode.run(
                                task,
                                StageGenerationRequest.<ProblemBundle>builder()
                                        .mode(StageGenerationMode.USER_REVISION)
                                        .existingArtifact(readExistingArtifact(context, ProblemBundle.class))
                                        .instruction(context.getInstruction())
                                        .baseStageVersion(context.getBaseStageVersion())
                                        .build(),
                                context)
                        : problemNormalizationNode.run(task, context),
                ProblemNormalizationNode.Result::getApiCalls);
        ProblemBundle problemBundle = nodeResult.getProblemBundle();

        ObjectNode resultJson = objectMapper.createObjectNode();
        resultJson.put("apiCalls", nodeResult.getApiCalls());
        resultJson.put("sourceType", nodeResult.getSourceType());
        resultJson.put("imageCount", nodeResult.getImageCount());
        resultJson.put("statementLength", nodeResult.getStatementLength());

        return MathVisionStageExecutionResult.builder()
                .artifactJson(toPrettyJson(problemBundle))
                .resultJson(toPrettyJson(resultJson))
                .changeSource(context.isUserRevision() ? "user_revision" : "initial_generation")
                .changeSummary(context.isUserRevision()
                        ? "regenerate complete problem normalization from user feedback"
                        : "complete problem normalization")
                .build();
    }

    private <T> T readExistingArtifact(MathVisionStageExecutionContext context, Class<T> type) {
        try {
            return objectMapper.readValue(context.getExistingArtifactJson(), type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse user-revision baseline artifact: " + e.getMessage(), e);
        }
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON: " + e.getMessage(), e);
        }
    }
}
