package com.kwang.study.mathvision.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class RenderResult {

    @JsonProperty("success")
    private boolean success;

    @JsonProperty("final_generated_code")
    private String finalGeneratedCode;

    @JsonProperty("scene_name")
    private String sceneName;

    @JsonProperty("video_path")
    private String videoPath;

    @JsonProperty("artifact_path")
    private String artifactPath;

    @JsonProperty("storage_artifact_path")
    private String storageArtifactPath;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @JsonProperty("artifact_receipt")
    private String artifactReceipt;

    @JsonProperty("local_artifact_path")
    private String localArtifactPath;

    @JsonProperty("artifact_file_name")
    private String artifactFileName;

    @JsonProperty("artifact_mime_type")
    private String artifactMimeType;

    @JsonProperty("geometry_path")
    private String geometryPath;

    @JsonProperty("output_target")
    private String outputTarget = "manim";

    @JsonProperty("artifact_type")
    private String artifactType;

    @JsonProperty("attempts")
    private int attempts;

    @JsonProperty("last_error")
    private String lastError;

    @JsonProperty("tool_calls")
    private int toolCalls;

    @JsonProperty("execution_time_seconds")
    private double executionTimeSeconds;
}
