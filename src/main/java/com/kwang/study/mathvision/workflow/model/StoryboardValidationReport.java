package com.kwang.study.mathvision.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoryboardValidationReport {

    @JsonProperty("validated")
    private boolean validated;

    @JsonProperty("passed")
    private boolean passed;

    @JsonProperty("output_target")
    private String outputTarget;

    @JsonProperty("scene_count")
    private int sceneCount;

    @JsonProperty("initial_issue_count")
    private int initialIssueCount;

    @JsonProperty("initial_issues")
    private List<String> initialIssues = new ArrayList<>();

    @JsonProperty("fix_attempted")
    private boolean fixAttempted;

    @JsonProperty("fix_applied")
    private boolean fixApplied;

    @JsonProperty("resolved_issue_count")
    private int resolvedIssueCount;

    @JsonProperty("final_issue_count")
    private int finalIssueCount;

    @JsonProperty("final_issues")
    private List<String> finalIssues = new ArrayList<>();

    @JsonProperty("total_validation_events")
    private int totalValidationEvents;

    @JsonProperty("entries")
    private List<StoryboardValidationTraceEntry> entries = new ArrayList<>();

    @JsonProperty("message")
    private String message;

    public void addEntry(StoryboardValidationTraceEntry entry) {
        if (entry == null) {
            return;
        }
        entries.add(entry);
        totalValidationEvents = entries.size();
    }
}
