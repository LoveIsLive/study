package com.kwang.study.mathvision.workflow.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class StoryboardValidationTraceEntry {

    @JsonProperty("sequence")
    private int sequence;

    @JsonProperty("phase")
    private String phase;

    @JsonProperty("cleanup_attempt")
    private int cleanupAttempt;

    @JsonProperty("passed")
    private boolean passed;

    @JsonProperty("scene_count")
    private int sceneCount;

    @JsonProperty("issue_count")
    private int issueCount;

    @JsonProperty("issues")
    private List<String> issues = new ArrayList<>();

    @JsonProperty("fix_attempted")
    private boolean fixAttempted;

    @JsonProperty("fix_applied")
    private boolean fixApplied;

    @JsonProperty("tool_calls")
    private int toolCalls;

    @JsonProperty("execution_time_seconds")
    private double executionTimeSeconds;

    @JsonProperty("message")
    private String message;
}
