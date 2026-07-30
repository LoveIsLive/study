package com.kwang.study.mathvision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionStageResultMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.MathVisionArtifact;
import com.kwang.study.mathvision.pojo.MathVisionStageResult;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.pojo.MathVisionVersion;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;

/** Builds the database equivalent of math-vision's 09_workflow_summary.json. */
@Service
public class MathVisionWorkflowSummaryService {

    private final MathVisionVersionMapper versionMapper;
    private final MathVisionArtifactMapper artifactMapper;
    private final MathVisionStageResultMapper stageResultMapper;
    private final ObjectMapper objectMapper;

    public MathVisionWorkflowSummaryService(MathVisionVersionMapper versionMapper,
                                            MathVisionArtifactMapper artifactMapper,
                                            MathVisionStageResultMapper stageResultMapper,
                                            ObjectMapper objectMapper) {
        this.versionMapper = versionMapper;
        this.artifactMapper = artifactMapper;
        this.stageResultMapper = stageResultMapper;
        this.objectMapper = objectMapper;
    }

    public String refresh(MathVisionTask task) {
        if (task == null || task.getId() == null || task.getCurrentVersion() == null) {
            return null;
        }
        MathVisionVersion version = versionMapper.findByTaskVersion(task.getId(), task.getCurrentVersion());
        if (version == null) {
            return null;
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("taskId", task.getId());
        root.put("version", version.getVersion());
        root.put("status", task.getStatus());
        root.put("currentStage", task.getCurrentStage());
        root.put("failedStage", task.getFailedStage());
        root.put("errorType", task.getErrorType());
        root.put("errorMessage", task.getErrorMessage());
        root.put("providerCode", task.getProviderCode());
        root.put("modelName", task.getModelName());
        root.put("outputTarget", task.getOutputTarget());
        root.put("updatedAt", Instant.now().toString());

        ObjectNode stages = root.putObject("stages");
        JsonNode pn = appendStage(stages, task.getId(), StageEnum.PROBLEM_NORMALIZATION, version.getPnVersion());
        JsonNode rg = appendStage(stages, task.getId(), StageEnum.REASONING_GRAPH, version.getRgVersion());
        JsonNode vs = appendStage(stages, task.getId(), StageEnum.VISUAL_STORYBOARD, version.getVsVersion());
        JsonNode cg = appendStage(stages, task.getId(), StageEnum.CODE_GENERATION, version.getCgVersion());
        JsonNode rr = appendStage(stages, task.getId(), StageEnum.RENDER_RESULT, version.getRrVersion());

        int totalApiCalls = apiCalls(pn) + apiCalls(rg) + apiCalls(vs) + apiCalls(cg) + apiCalls(rr);
        root.put("totalApiCalls", totalApiCalls);
        root.put("problemSourceType", text(pn, "sourceType"));
        root.put("graphNodeCount", integer(rg, "nodeCount"));
        root.put("graphEdgeCount", integer(rg, "edgeCount"));
        root.put("storyboardSceneCount", integer(vs, "sceneCount"));
        root.put("storyboardObjectCount", integer(vs, "objectCount"));
        root.put("storyboardValidationCompleted", bool(vs, "validationCompleted"));
        root.put("codeLines", integer(cg, "lineCount"));
        root.put("codeGenerationAttempts", integer(cg, "codeGenerationAttempts"));
        root.put("codeGateReason", text(cg, "codeEvaluationGateReason"));
        root.put("renderSuccess", bool(rr, "renderSuccess"));
        root.put("renderFinalSuccess", bool(rr, "renderFinalSuccess"));
        root.put("sceneEvaluationApproved", bool(rr, "sceneEvaluationApproved"));
        root.put("sceneEvaluationGateReason", rr.path("sceneEvaluation").path("gateReason").asText(""));
        root.put("renderAttempts", rr.path("renderResult").path("attempts").asInt(0));
        root.put("renderLastError", rr.path("renderResult").path("lastError").asText(""));
        root.put("finalArtifactPath", rr.path("renderResult").path("artifactPath").asText(""));

        String summary = pretty(root);
        versionMapper.updateWorkflowSummary(task.getId(), version.getVersion(), summary);
        return summary;
    }

    private JsonNode appendStage(ObjectNode stages,
                                 Long taskId,
                                 StageEnum stage,
                                 Integer stageVersion) {
        ObjectNode stageNode = stages.putObject(stage.getCode());
        if (stageVersion == null) {
            stageNode.put("available", false);
            return objectMapper.createObjectNode();
        }
        stageNode.put("available", true);
        stageNode.put("version", stageVersion);
        MathVisionArtifact artifact = artifactMapper.findByTaskStageVersion(
                taskId, stage.getCode(), stageVersion);
        if (artifact != null) {
            stageNode.put("artifactId", artifact.getId());
            stageNode.put("changeSource", artifact.getChangeSource());
            stageNode.put("changeSummary", artifact.getChangeSummary());
        }
        MathVisionStageResult result = stageResultMapper.findByTaskStageVersion(
                taskId, stage.getCode(), stageVersion);
        JsonNode resultJson = parse(result != null ? result.getResultJson() : null);
        stageNode.set("result", resultJson);
        return resultJson;
    }

    private JsonNode parse(String value) {
        if (!StringUtils.hasText(value)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            ObjectNode invalid = objectMapper.createObjectNode();
            invalid.put("parseError", e.getMessage());
            return invalid;
        }
    }

    private int apiCalls(JsonNode node) {
        return integer(node, "apiCalls");
    }

    private int integer(JsonNode node, String field) {
        return node != null ? node.path(field).asInt(0) : 0;
    }

    private boolean bool(JsonNode node, String field) {
        return node != null && node.path(field).asBoolean(false);
    }

    private String text(JsonNode node, String field) {
        return node != null ? node.path(field).asText("") : "";
    }

    private String pretty(JsonNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize workflow summary", e);
        }
    }
}
