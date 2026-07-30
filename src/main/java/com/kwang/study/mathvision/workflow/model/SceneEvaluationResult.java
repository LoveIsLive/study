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

    @JsonProperty("revisionTriggered")
    private boolean revisionTriggered;

    @JsonProperty("revisionAttempts")
    private int revisionAttempts;

    @JsonProperty("renderSuccess")
    private boolean renderSuccess;

    @JsonProperty("sceneName")
    private String sceneName;

    @JsonProperty("geometryPath")
    private String geometryPath;

    @JsonProperty("gateReason")
    private String gateReason;

    @JsonProperty("sampleCount")
    private int sampleCount;

    @JsonProperty("issueSampleCount")
    private int issueSampleCount;

    @JsonProperty("totalIssueCount")
    private int totalIssueCount;

    @JsonProperty("overlapIssueCount")
    private int overlapIssueCount;

    @JsonProperty("offscreenIssueCount")
    private int offscreenIssueCount;

    @JsonProperty("blockingIssueCount")
    private int blockingIssueCount;

    @JsonProperty("toolCalls")
    private int toolCalls;

    @JsonProperty("executionTimeSeconds")
    private double executionTimeSeconds;

    @JsonProperty("samples")
    private List<SampleEvaluation> samples = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class SampleEvaluation {
        @JsonProperty("sampleId")
        private String sampleId;
        @JsonProperty("playIndex")
        private Integer playIndex;
        @JsonProperty("sampleRole")
        private String sampleRole;
        @JsonProperty("trigger")
        private String trigger;
        @JsonProperty("sceneMethod")
        private String sceneMethod;
        @JsonProperty("sourceCode")
        private String sourceCode;
        @JsonProperty("elementCount")
        private int elementCount;
        @JsonProperty("hasIssues")
        private boolean hasIssues;
        @JsonProperty("issueCount")
        private int issueCount;
        @JsonProperty("overlapIssueCount")
        private int overlapIssueCount;
        @JsonProperty("offscreenIssueCount")
        private int offscreenIssueCount;
        @JsonProperty("blockingIssueCount")
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
        @JsonProperty("reasonCode")
        private String reasonCode;
        @JsonProperty("likelyFalsePositive")
        private Boolean likelyFalsePositive;
        @JsonProperty("recommendedAction")
        private String recommendedAction;
        @JsonProperty("primaryElement")
        private ElementRef primaryElement;
        @JsonProperty("secondaryElement")
        private ElementRef secondaryElement;
        @JsonProperty("overflow")
        private Overflow overflow;
        @JsonProperty("intersectionArea")
        private Double intersectionArea;
        @JsonProperty("intersectionBounds")
        private Bounds intersectionBounds;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class ElementRef {
        @JsonProperty("stableId")
        private String stableId;
        @JsonProperty("semanticName")
        private String semanticName;
        @JsonProperty("className")
        private String className;
        @JsonProperty("sampleRole")
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
