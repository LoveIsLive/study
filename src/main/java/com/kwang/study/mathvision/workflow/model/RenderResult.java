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

    @JsonProperty("finalGeneratedCode")
    private String finalGeneratedCode;

    @JsonProperty("sceneName")
    private String sceneName;

    @JsonProperty("videoPath")
    private String videoPath;

    @JsonProperty("artifactPath")
    private String artifactPath;

    @JsonProperty("storageArtifactPath")
    private String storageArtifactPath;

    @JsonProperty("localArtifactPath")
    private String localArtifactPath;

    @JsonProperty("artifactFileName")
    private String artifactFileName;

    @JsonProperty("artifactMimeType")
    private String artifactMimeType;

    @JsonProperty("geometryPath")
    private String geometryPath;

    @JsonProperty("outputTarget")
    private String outputTarget = "manim";

    @JsonProperty("artifactType")
    private String artifactType;

    @JsonProperty("attempts")
    private int attempts;

    @JsonProperty("lastError")
    private String lastError;

    @JsonProperty("toolCalls")
    private int toolCalls;

    @JsonProperty("executionTimeSeconds")
    private double executionTimeSeconds;
}
