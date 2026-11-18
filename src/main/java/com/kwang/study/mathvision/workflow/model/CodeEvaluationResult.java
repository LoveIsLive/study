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

    @JsonProperty("total_evaluations")
    private int totalEvaluations;

    @JsonProperty("approved_for_render")
    private boolean approvedForRender;

    @JsonProperty("revision_triggered")
    private boolean revisionTriggered;

    @JsonProperty("revised_code_applied")
    private boolean revisedCodeApplied;

    @JsonProperty("revision_attempts")
    private int revisionAttempts;

    @JsonProperty("tool_calls")
    private int toolCalls;

    @JsonProperty("gate_reason")
    private String gateReason;

    @JsonProperty("scene_name")
    private String sceneName;

    @JsonProperty("initial_static_analysis")
    private StaticAnalysis initialStaticAnalysis;

    @JsonProperty("final_static_analysis")
    private StaticAnalysis finalStaticAnalysis;

    @JsonProperty("initial_review")
    private ReviewSnapshot initialReview;

    @JsonProperty("final_review")
    private ReviewSnapshot finalReview;

    @JsonProperty("execution_time_seconds")
    private double executionTimeSeconds;

    @JsonProperty("attempts")
    private List<EvaluationAttempt> attempts = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class EvaluationAttempt {
        @JsonProperty("sequence")
        private int sequence;
        @JsonProperty("approved_for_render")
        private boolean approvedForRender;
        @JsonProperty("gate_reason")
        private String gateReason;
        @JsonProperty("scene_name")
        private String sceneName;
        @JsonProperty("static_analysis")
        private StaticAnalysis staticAnalysis;
        @JsonProperty("review")
        private ReviewSnapshot review;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class StaticAnalysis {
        @JsonProperty("code_lines")
        private int codeLines;
        @JsonProperty("scene_count")
        private int sceneCount;
        @JsonProperty("to_edge_count")
        private int toEdgeCount;
        @JsonProperty("shift_count")
        private int shiftCount;
        @JsonProperty("large_shift_count")
        private int largeShiftCount;
        @JsonProperty("fade_in_count")
        private int fadeInCount;
        @JsonProperty("fade_out_count")
        private int fadeOutCount;
        @JsonProperty("transform_count")
        private int transformCount;
        @JsonProperty("replacement_transform_count")
        private int replacementTransformCount;
        @JsonProperty("fade_transform_count")
        private int fadeTransformCount;
        @JsonProperty("arrange_count")
        private int arrangeCount;
        @JsonProperty("next_to_count")
        private int nextToCount;
        @JsonProperty("class_name")
        private String className;
        @JsonProperty("uses_manim_import")
        private boolean usesManimImport;
        @JsonProperty("has_construct")
        private boolean hasConstruct;
        @JsonProperty("math_tex_count")
        private int mathTexCount;
        @JsonProperty("text_count")
        private int textCount;
        @JsonProperty("three_dscene")
        private boolean threeDScene;
        @JsonProperty("three_dstoryboard_scene_count")
        private int threeDStoryboardSceneCount;
        @JsonProperty("three_dobject_count")
        private int threeDObjectCount;
        @JsonProperty("max_entering_objects")
        private int maxEnteringObjects;
        @JsonProperty("max_visible_objects")
        private int maxVisibleObjects;
        @JsonProperty("max_visible_textual_objects")
        private int maxVisibleTextualObjects;
        @JsonProperty("max_narration_words_per_second")
        private double maxNarrationWordsPerSecond;
        @JsonProperty("min_narration_words_per_second")
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
        @JsonProperty("rule_id")
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
        @JsonProperty("approved_for_render")
        private boolean approvedForRender;
        @JsonProperty("rule_checks")
        private List<RuleCheck> ruleChecks = new ArrayList<>();
        @JsonProperty("summary")
        private String summary;
        @JsonProperty("strengths")
        private List<String> strengths = new ArrayList<>();
        @JsonProperty("blocking_issues")
        private List<String> blockingIssues = new ArrayList<>();
        @JsonProperty("revision_directives")
        private List<String> revisionDirectives = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class RuleCheck {
        @JsonProperty("severity")
        private String severity;
        @JsonProperty("rule_id")
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
