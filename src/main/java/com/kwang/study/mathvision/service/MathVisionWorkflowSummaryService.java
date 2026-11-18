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
        root.put("task_id", task.getId());
        root.put("version", version.getVersion());
        root.put("status", task.getStatus());
        root.put("current_stage", task.getCurrentStage());
        root.put("failed_stage", task.getFailedStage());
        root.put("error_type", task.getErrorType());
        root.put("error_message", task.getErrorMessage());
        root.put("provider_code", task.getProviderCode());
        root.put("model_name", task.getModelName());
        root.put("output_target", task.getOutputTarget());
        root.put("updated_at", Instant.now().toString());

        ObjectNode stages = root.putObject("stages");
        JsonNode pn = appendStage(stages, task.getId(), StageEnum.PROBLEM_NORMALIZATION, version.getPnVersion());
        JsonNode rg = appendStage(stages, task.getId(), StageEnum.REASONING_GRAPH, version.getRgVersion());
        JsonNode vs = appendStage(stages, task.getId(), StageEnum.VISUAL_STORYBOARD, version.getVsVersion());
        JsonNode cg = appendStage(stages, task.getId(), StageEnum.CODE_GENERATION, version.getCgVersion());
        JsonNode rr = appendStage(stages, task.getId(), StageEnum.RENDER_RESULT, version.getRrVersion());

        int totalApiCalls = apiCalls(pn) + apiCalls(rg) + apiCalls(vs) + apiCalls(cg) + apiCalls(rr);
        root.put("total_api_calls", totalApiCalls);
        root.put("problem_source_type", text(pn, "source_type"));
        root.put("graph_node_count", integer(rg, "node_count"));
        root.put("graph_edge_count", integer(rg, "edge_count"));
        root.put("storyboard_scene_count", integer(vs, "scene_count"));
        root.put("storyboard_object_count", integer(vs, "object_count"));
        root.put("storyboard_validation_completed", bool(vs, "validation_completed"));
        root.put("code_lines", integer(cg, "line_count"));
        root.put("code_generation_attempts", integer(cg, "code_generation_attempts"));
        root.put("code_gate_reason", text(cg, "code_evaluation_gate_reason"));
        root.put("render_success", bool(rr, "render_success"));
        root.put("render_final_success", bool(rr, "render_final_success"));
        root.put("scene_evaluation_approved", bool(rr, "scene_evaluation_approved"));
        root.put("scene_evaluation_gate_reason",
                rr.path("scene_evaluation").path("gate_reason").asText(""));
        root.put("render_attempts", rr.path("render_result").path("attempts").asInt(0));
        root.put("render_last_error", rr.path("render_result").path("last_error").asText(""));
        root.put("final_artifact_path", rr.path("render_result").path("artifact_path").asText(""));

        JsonNode prior = parse(version.getWorkflowSummaryJson());
        if (prior.has("execution_commits")) root.set("execution_commits", prior.get("execution_commits"));
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
            stageNode.put("artifact_id", artifact.getId());
            stageNode.put("change_source", artifact.getChangeSource());
            stageNode.put("change_summary", artifact.getChangeSummary());
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
            invalid.put("parse_error", e.getMessage());
            return invalid;
        }
    }

    private int apiCalls(JsonNode node) {
        return integer(node, "api_calls");
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
