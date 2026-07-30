package com.kwang.study.mathvision.workflow.prompt;

import com.kwang.study.mathvision.workflow.model.CodeFixRequest;
import com.kwang.study.mathvision.workflow.model.CodeFixSource;

public final class CodeFixPrompts {

    private CodeFixPrompts() {
    }

    public static String buildRulesPrompt(CodeFixRequest request) {
        if (hasText(request != null ? request.getRulesPrompt() : null)) {
            return request.getRulesPrompt();
        }
        if (request != null && request.getSource() == CodeFixSource.CODE_EVALUATION) {
            return CodeEvaluationPrompts.buildRevisionRulesPrompt(outputTarget(request));
        }
        if (request != null && request.getSource() == CodeFixSource.SCENE_LAYOUT_EVALUATION) {
            return SceneEvaluationPrompts.buildLayoutFixRulesPrompt(outputTarget(request));
        }
        if (request != null && request.getSource() == CodeFixSource.CODE_RENDER) {
            return RenderFixPrompts.buildRulesPrompt(outputTarget(request));
        }
        String source = request == null || request.getSource() == null ? "CODE_FIX" : request.getSource().name();
        return SystemPrompts.buildRulesSection(
                "Shared Code Fix / " + source + "\n"
                        + "- Return the complete fixed code, not a patch or explanation.\n"
                        + "- Preserve the approved storyboard, mathematical meaning, and teaching order.\n"
                        + "- Fix only issues evidenced by the current request.\n"
                        + "- For Manim, keep the renderable scene class named MainScene and preserve the original Scene-compatible base class.");
    }

    public static String buildFixedContextPrompt(CodeFixRequest request) {
        if (hasText(request != null ? request.getFixedContextPrompt() : null)) {
            return request.getFixedContextPrompt();
        }
        if (request != null && request.getSource() == CodeFixSource.CODE_EVALUATION) {
            return CodeEvaluationPrompts.buildRevisionFixedContextPrompt(
                    request.getProblemBundle(),
                    firstNonBlank(request.getTargetDescription(), ""),
                    outputTarget(request));
        }
        if (request != null && request.getSource() == CodeFixSource.SCENE_LAYOUT_EVALUATION) {
            return SceneEvaluationPrompts.buildLayoutFixFixedContextPrompt(
                    outputTarget(request));
        }
        if (request != null && request.getSource() == CodeFixSource.CODE_RENDER) {
            return RenderFixPrompts.buildFixedContextPrompt(
                    request.getProblemBundle(),
                    firstNonBlank(request.getTargetDescription(), ""),
                    outputTarget(request),
                    request.getStoryboardJson());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Output target: ").append(request.getOutputTarget()).append('\n');
        if (request.getTargetDescription() != null) {
            sb.append("Target description:\n").append(request.getTargetDescription()).append("\n\n");
        }
        if (request.getStoryboardJson() != null) {
            sb.append("Storyboard JSON:\n").append(request.getStoryboardJson()).append("\n\n");
        }
        return SystemPrompts.buildFixedContextSection(sb.toString());
    }

    public static String buildCurrentRequestPrompt(CodeFixRequest request) {
        if (request != null && request.getSource() == CodeFixSource.CODE_EVALUATION) {
            String artifactName = firstNonBlank(
                    request.getSceneName(),
                    request.getExpectedSceneName(),
                    "geogebra".equalsIgnoreCase(outputTarget(request)) ? "GeoGebraFigure" : "MainScene");
            return CodeEvaluationPrompts.revisionUserPrompt(
                    artifactName,
                    defaultIfBlank(request.getStoryboardJson(), StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON),
                    defaultIfBlank(request.getStaticAnalysisJson(), "{}"),
                    defaultIfBlank(request.getReviewJson(), "{}"),
                    request.getGeneratedCode(),
                    outputTarget(request));
        }
        if (request != null && request.getSource() == CodeFixSource.CODE_RENDER) {
            if ("geogebra".equalsIgnoreCase(outputTarget(request))) {
                return RenderFixPrompts.geoGebraUserPrompt(
                        request.getGeneratedCode(),
                        firstNonBlank(request.getErrorReason(), request.getRenderError(), "Unknown render failure"),
                        defaultIfBlank(request.getStoryboardJson(), StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON),
                        request.getFixHistory());
            }
            return RenderFixPrompts.manimUserPrompt(
                    request.getGeneratedCode(),
                    firstNonBlank(request.getErrorReason(), request.getRenderError(), "Unknown render failure"),
                    defaultIfBlank(request.getStoryboardJson(), StoryboardJsonBuilder.EMPTY_STORYBOARD_JSON),
                    request.getFixHistory(),
                    request.getErrorContextMode(),
                    request.getStaticAuditSummary());
        }
        if (request != null && request.getSource() == CodeFixSource.SCENE_LAYOUT_EVALUATION) {
            if ("geogebra".equalsIgnoreCase(outputTarget(request))) {
                return SceneEvaluationPrompts.geoGebraLayoutFixUserPrompt(
                        request.getStoryboardJson(),
                        request.getGeneratedCode(),
                        firstNonBlank(request.getErrorReason(), "Unknown scene evaluation issue"),
                        request.getSceneEvaluationJson(),
                        request.getFixHistory());
            }
            return SceneEvaluationPrompts.manimLayoutFixUserPrompt(
                    request.getStoryboardJson(),
                    request.getGeneratedCode(),
                    firstNonBlank(request.getErrorReason(), "Unknown scene evaluation issue"),
                    request.getSceneEvaluationJson(),
                    request.getFixHistory());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Fix source: ").append(request.getSource()).append('\n');
        sb.append("Scene name: ").append(request.getSceneName()).append('\n');
        sb.append("Expected scene name: ").append(request.getExpectedSceneName()).append("\n\n");
        sb.append("Error reason:\n").append(nullToEmpty(request.getErrorReason())).append("\n\n");
        if (request.getStaticAnalysisJson() != null) {
            sb.append("Static analysis JSON:\n").append(request.getStaticAnalysisJson()).append("\n\n");
        }
        if (request.getReviewJson() != null) {
            sb.append("Review JSON:\n").append(request.getReviewJson()).append("\n\n");
        }
        if (request.getRenderError() != null) {
            sb.append("Render error:\n").append(request.getRenderError()).append("\n\n");
        }
        if (request.getSceneEvaluationJson() != null) {
            sb.append("Scene evaluation JSON:\n").append(request.getSceneEvaluationJson()).append("\n\n");
        }
        if (request.getFixHistory() != null && !request.getFixHistory().isEmpty()) {
            sb.append("Prior fix history:\n").append(String.join("\n", request.getFixHistory())).append("\n\n");
        }
        sb.append("Current code:\n```text\n")
                .append(nullToEmpty(request.getGeneratedCode()))
                .append("\n```");
        return SystemPrompts.buildCurrentRequestSection(sb.toString());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String outputTarget(CodeFixRequest request) {
        return request != null && request.getOutputTarget() != null && !request.getOutputTarget().isBlank()
                ? request.getOutputTarget()
                : "manim";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
