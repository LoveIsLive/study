package com.kwang.study.mathvision.workflow.prompt;

import com.kwang.study.mathvision.workflow.model.CodeFixRequest;
import com.kwang.study.mathvision.workflow.model.CodeFixSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeFixPromptTest {

    @Test
    void renderFixPromptCarriesStaticPreflightFindingsAndCoordinateContract() {
        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.CODE_RENDER);
        request.setOutputTarget("manim");
        request.setGeneratedCode("class MainScene(Scene):\n    def construct(self):\n        pass");
        request.setErrorReason("Render preflight failed");
        request.setErrorContextMode("summary_signature");
        request.setStaticAuditIssueCount(2);
        request.setStaticAuditSummary("generated coordinate skeleton must derive one uniform `_mv_unit_scale` from storyboard bounds");
        request.setFixHistory(List.of("attempt 1: outcome=FAILED"));

        String rules = CodeFixPrompts.buildRulesPrompt(request);
        String current = CodeFixPrompts.buildCurrentRequestPrompt(request);

        assertTrue(rules.contains("coordinate_bounds"));
        assertTrue(rules.contains("one uniform screen scale"));
        assertTrue(rules.contains("Circle"));
        assertTrue(rules.contains("Arc"));
        assertTrue(rules.contains("Complete-artifact code-fix preservation rules"));
        assertTrue(rules.contains("never a sample, placeholder, title-only program"));
        assertTrue(current.contains("Static preflight findings"));
        assertTrue(current.contains("summary_signature"));
        assertTrue(current.contains("Previous fix attempts"));
    }

    @Test
    void sceneEvaluationFixUsesLayoutSpecificPrompt() {
        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.SCENE_LAYOUT_EVALUATION);
        request.setOutputTarget("manim");
        request.setGeneratedCode("class MainScene(Scene):\n    def construct(self):\n        pass");
        request.setErrorReason("label overlaps target segment");
        request.setSceneEvaluationJson("{\"approved\":false}");

        String rules = CodeFixPrompts.buildRulesPrompt(request);
        String current = CodeFixPrompts.buildCurrentRequestPrompt(request);

        assertTrue(rules.contains("Scene evaluation repair requirements"));
        assertTrue(rules.contains("rendered geometry report"));
        assertTrue(current.contains("post-render scene evaluation layout report"));
        assertTrue(current.contains("Scene evaluation report excerpt"));
    }

}
