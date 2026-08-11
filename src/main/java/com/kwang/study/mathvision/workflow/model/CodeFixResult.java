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
public class CodeFixResult {

    public enum FixOutcome {
        FIXED,
        APPLIED_WITH_ISSUES,
        UNCHANGED,
        REJECTED_CONTRACT,
        INPUT_CORRUPTED,
        RATE_LIMIT_BLOCKED,
        FAILED
    }

    @JsonProperty("outcome")
    private FixOutcome outcome;

    @JsonProperty("source")
    private CodeFixSource source;

    @JsonProperty("originalGeneratedCode")
    private String originalGeneratedCode;

    @JsonProperty("fixedGeneratedCode")
    private String fixedGeneratedCode;

    @JsonProperty("errorReason")
    private String errorReason;

    @JsonProperty("rulesPrompt")
    private String rulesPrompt;

    @JsonProperty("fixedContextPrompt")
    private String fixedContextPrompt;

    @JsonProperty("currentRequestPrompt")
    private String currentRequestPrompt;

    @JsonProperty("failureReason")
    private String failureReason;

    @JsonProperty("postFixStaticAuditIssueCount")
    private int postFixStaticAuditIssueCount;

    @JsonProperty("postFixStaticAuditSummary")
    private String postFixStaticAuditSummary;

    @JsonProperty("acceptanceIssues")
    private List<String> acceptanceIssues = new ArrayList<>();

    @JsonProperty("acceptanceWarnings")
    private List<String> acceptanceWarnings = new ArrayList<>();

    @JsonProperty("applied")
    private boolean applied;

    @JsonProperty("toolCalls")
    private int toolCalls;

    @JsonProperty("executionTimeSeconds")
    private double executionTimeSeconds;
}
