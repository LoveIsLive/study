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
public class CodeEvaluationResult {

    @JsonProperty("totalEvaluations")
    private int totalEvaluations;

    @JsonProperty("approvedForRender")
    private boolean approvedForRender;

    @JsonProperty("revisionTriggered")
    private boolean revisionTriggered;

    @JsonProperty("revisedCodeApplied")
    private boolean revisedCodeApplied;

    @JsonProperty("revisionAttempts")
    private int revisionAttempts;

    @JsonProperty("toolCalls")
    private int toolCalls;

    @JsonProperty("gateReason")
    private String gateReason;

    @JsonProperty("sceneName")
    private String sceneName;

    @JsonProperty("initialStaticAnalysis")
    private StaticAnalysis initialStaticAnalysis;

    @JsonProperty("finalStaticAnalysis")
    private StaticAnalysis finalStaticAnalysis;

    @JsonProperty("initialReview")
    private ReviewSnapshot initialReview;

    @JsonProperty("finalReview")
    private ReviewSnapshot finalReview;

    @JsonProperty("executionTimeSeconds")
    private double executionTimeSeconds;

    @JsonProperty("attempts")
    private List<EvaluationAttempt> attempts = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class EvaluationAttempt {
        @JsonProperty("sequence")
        private int sequence;
        @JsonProperty("approvedForRender")
        private boolean approvedForRender;
        @JsonProperty("gateReason")
        private String gateReason;
        @JsonProperty("sceneName")
        private String sceneName;
        @JsonProperty("staticAnalysis")
        private StaticAnalysis staticAnalysis;
        @JsonProperty("review")
        private ReviewSnapshot review;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class StaticAnalysis {
        @JsonProperty("codeLines")
        private int codeLines;
        @JsonProperty("sceneCount")
        private int sceneCount;
        private int toEdgeCount;
        private int shiftCount;
        private int largeShiftCount;
        private int fadeInCount;
        private int fadeOutCount;
        private int transformCount;
        private int replacementTransformCount;
        private int fadeTransformCount;
        private int arrangeCount;
        private int nextToCount;
        @JsonProperty("className")
        private String className;
        @JsonProperty("usesManimImport")
        private boolean usesManimImport;
        @JsonProperty("hasConstruct")
        private boolean hasConstruct;
        @JsonProperty("mathTexCount")
        private int mathTexCount;
        @JsonProperty("textCount")
        private int textCount;
        private boolean threeDScene;
        private int threeDStoryboardSceneCount;
        private int threeDObjectCount;
        private int maxEnteringObjects;
        private int maxVisibleObjects;
        private int maxVisibleTextualObjects;
        private double maxNarrationWordsPerSecond;
        private double minNarrationWordsPerSecond;
        @JsonProperty("findings")
        private List<StaticFinding> findings = new ArrayList<>();

        public boolean hasBlockingFindings() {
            return findings != null
                    && findings.stream().anyMatch(finding -> "fail".equalsIgnoreCase(finding.getSeverity()));
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class StaticFinding {
        @JsonProperty("ruleId")
        private String ruleId;
        @JsonProperty("severity")
        private String severity;
        @JsonProperty("summary")
        private String summary;
        @JsonProperty("evidence")
        private String evidence;

        public StaticFinding() {
        }

        public StaticFinding(String ruleId, String severity, String summary, String evidence) {
            this.ruleId = ruleId;
            this.severity = severity;
            this.summary = summary;
            this.evidence = evidence;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class ReviewSnapshot {
        @JsonProperty("approvedForRender")
        private boolean approvedForRender;
        @JsonProperty("ruleChecks")
        private List<RuleCheck> ruleChecks = new ArrayList<>();
        @JsonProperty("summary")
        private String summary;
        @JsonProperty("strengths")
        private List<String> strengths = new ArrayList<>();
        @JsonProperty("blockingIssues")
        private List<String> blockingIssues = new ArrayList<>();
        @JsonProperty("revisionDirectives")
        private List<String> revisionDirectives = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class RuleCheck {
        @JsonProperty("severity")
        private String severity;
        @JsonProperty("ruleId")
        private String ruleId;
        @JsonProperty("requirement")
        private String requirement;
        @JsonProperty("status")
        private String status;
        @JsonProperty("evidence")
        private String evidence;

        public RuleCheck() {
        }

        public RuleCheck(String ruleId, String requirement, String status, String evidence, String severity) {
            this.ruleId = ruleId;
            this.requirement = requirement;
            this.status = status;
            this.evidence = evidence;
            this.severity = severity;
        }
    }
}
