package com.kwang.study.mathvision.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class CodeFixRequest {

    @JsonProperty("source")
    private CodeFixSource source;

    @JsonProperty("generatedCode")
    private String generatedCode;

    @JsonProperty("errorReason")
    private String errorReason;

    @JsonProperty("problemBundle")
    private ProblemBundle problemBundle;

    @JsonProperty("targetDescription")
    private String targetDescription;

    @JsonProperty("sceneName")
    private String sceneName;

    @JsonProperty("expectedSceneName")
    private String expectedSceneName;

    @JsonProperty("outputTarget")
    private String outputTarget = "manim";

    @JsonProperty("storyboardJson")
    private String storyboardJson;

    @JsonProperty("staticAnalysisJson")
    private String staticAnalysisJson;

    @JsonProperty("reviewJson")
    private String reviewJson;

    @JsonProperty("renderError")
    private String renderError;

    @JsonProperty("sceneEvaluationJson")
    private String sceneEvaluationJson;

    @JsonProperty("errorContextMode")
    private String errorContextMode;

    @JsonProperty("inputTextHealth")
    private String inputTextHealth;

    @JsonProperty("staticAuditIssueCount")
    private int staticAuditIssueCount;

    @JsonProperty("staticAuditSummary")
    private String staticAuditSummary;

    @JsonProperty("rulesPrompt")
    private String rulesPrompt;

    @JsonProperty("fixedContextPrompt")
    private String fixedContextPrompt;

    @JsonProperty("fixHistory")
    private List<String> fixHistory = new ArrayList<>();
}
