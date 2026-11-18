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
public class SceneEvaluationResult {

    @JsonProperty("evaluated")
    private boolean evaluated;

    @JsonProperty("approved")
    private boolean approved;

    @JsonProperty("revision_triggered")
    private boolean revisionTriggered;

    @JsonProperty("revision_attempts")
    private int revisionAttempts;

    @JsonProperty("render_success")
    private boolean renderSuccess;

    @JsonProperty("scene_name")
    private String sceneName;

    @JsonProperty("geometry_path")
    private String geometryPath;

    @JsonProperty("gate_reason")
    private String gateReason;

    @JsonProperty("sample_count")
    private int sampleCount;

    @JsonProperty("issue_sample_count")
    private int issueSampleCount;

    @JsonProperty("total_issue_count")
    private int totalIssueCount;

    @JsonProperty("overlap_issue_count")
    private int overlapIssueCount;

    @JsonProperty("offscreen_issue_count")
    private int offscreenIssueCount;

    @JsonProperty("blocking_issue_count")
    private int blockingIssueCount;

    @JsonProperty("tool_calls")
    private int toolCalls;

    @JsonProperty("execution_time_seconds")
    private double executionTimeSeconds;

    @JsonProperty("samples")
    private List<SampleEvaluation> samples = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class SampleEvaluation {
        @JsonProperty("sample_id")
        private String sampleId;
        @JsonProperty("play_index")
        private Integer playIndex;
        @JsonProperty("sample_role")
        private String sampleRole;
        @JsonProperty("trigger")
        private String trigger;
        @JsonProperty("scene_method")
        private String sceneMethod;
        @JsonProperty("source_code")
        private String sourceCode;
        @JsonProperty("element_count")
        private int elementCount;
        @JsonProperty("has_issues")
        private boolean hasIssues;
        @JsonProperty("issue_count")
        private int issueCount;
        @JsonProperty("overlap_issue_count")
        private int overlapIssueCount;
        @JsonProperty("offscreen_issue_count")
        private int offscreenIssueCount;
        @JsonProperty("blocking_issue_count")
        private int blockingIssueCount;
        @JsonProperty("issues")
        private List<LayoutIssue> issues = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class LayoutIssue {
        @JsonProperty("type")
        private String type;
        @JsonProperty("message")
        private String message;
        @JsonProperty("severity")
        private String severity;
        @JsonProperty("reason_code")
        private String reasonCode;
        @JsonProperty("likely_false_positive")
        private Boolean likelyFalsePositive;
        @JsonProperty("recommended_action")
        private String recommendedAction;
        @JsonProperty("primary_element")
        private ElementRef primaryElement;
        @JsonProperty("secondary_element")
        private ElementRef secondaryElement;
        @JsonProperty("overflow")
        private Overflow overflow;
        @JsonProperty("intersection_area")
        private Double intersectionArea;
        @JsonProperty("intersection_bounds")
        private Bounds intersectionBounds;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class ElementRef {
        @JsonProperty("stable_id")
        private String stableId;
        @JsonProperty("semantic_name")
        private String semanticName;
        @JsonProperty("class_name")
        private String className;
        @JsonProperty("sample_role")
        private String sampleRole;
        @JsonProperty("bounds")
        private Bounds bounds;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Overflow {
        @JsonProperty("left")
        private double left;
        @JsonProperty("right")
        private double right;
        @JsonProperty("top")
        private double top;
        @JsonProperty("bottom")
        private double bottom;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Bounds {
        @JsonProperty("min")
        private double[] min;
        @JsonProperty("max")
        private double[] max;
    }
}
