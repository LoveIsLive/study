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

    @JsonProperty("original_generated_code")
    private String originalGeneratedCode;

    @JsonProperty("fixed_generated_code")
    private String fixedGeneratedCode;

    @JsonProperty("error_reason")
    private String errorReason;

    @JsonProperty("rules_prompt")
    private String rulesPrompt;

    @JsonProperty("fixed_context_prompt")
    private String fixedContextPrompt;

    @JsonProperty("current_request_prompt")
    private String currentRequestPrompt;

    @JsonProperty("failure_reason")
    private String failureReason;

    @JsonProperty("post_fix_static_audit_issue_count")
    private int postFixStaticAuditIssueCount;

    @JsonProperty("post_fix_static_audit_summary")
    private String postFixStaticAuditSummary;

    @JsonProperty("acceptance_issues")
    private List<String> acceptanceIssues = new ArrayList<>();

    @JsonProperty("acceptance_warnings")
    private List<String> acceptanceWarnings = new ArrayList<>();

    @JsonProperty("applied")
    private boolean applied;

    @JsonProperty("tool_calls")
    private int toolCalls;

    @JsonProperty("execution_time_seconds")
    private double executionTimeSeconds;
}
