package com.kwang.study.mathvision.workflow.util;

import com.kwang.study.mathvision.workflow.model.CodeFixSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeFixAcceptanceValidatorTest {

    @Test
    void acceptsFocusedRewriteThatPreservesEstablishedScenes() {
        String original = manimCode(
                "VoiceoverScene",
                "        self.play(Create(dot))\n        self.wait(1)",
                "        with self.voiceover(text=\"第二场景\"):\n            self.play(dot.animate.shift(RIGHT))");
        String candidate = manimCode(
                "VoiceoverScene",
                "        self.play(FadeIn(dot))",
                "        with self.voiceover(text=\"第二场景\"):\n            self.play(dot.animate.shift(2 * RIGHT))");

        CodeFixAcceptanceValidator.Decision decision = CodeFixAcceptanceValidator.evaluate(
                original, candidate, "manim", CodeFixSource.CODE_RENDER);

        assertTrue(decision.isAccepted(), decision::summarizeIssues);
    }

    @Test
    void acceptsAdditionalHelpersAndScenesWithoutTreatingThemAsDataLoss() {
        String original = manimCode("Scene", "        self.wait(1)", "        self.wait(1)");
        String candidate = original
                + "\n    def scene_3(self):\n        self.wait(0.5)"
                + "\n    def helper(self):\n        return ORIGIN\n";

        CodeFixAcceptanceValidator.Decision decision = CodeFixAcceptanceValidator.evaluate(
                original, candidate, "manim", CodeFixSource.CODE_EVALUATION);

        assertTrue(decision.isAccepted(), decision::summarizeIssues);
        assertFalse(decision.getWarnings().isEmpty());
    }

    @Test
    void allowsBaseClassRepairAndReportsItForReview() {
        String original = manimCode("Scene", "        self.wait(1)", "        self.wait(1)");
        String candidate = manimCode(
                "VoiceoverScene",
                "        self.wait(1)",
                "        self.wait(1)");

        CodeFixAcceptanceValidator.Decision decision = CodeFixAcceptanceValidator.evaluate(
                original, candidate, "manim", CodeFixSource.CODE_RENDER);

        assertTrue(decision.isAccepted(), decision::summarizeIssues);
        assertTrue(decision.getWarnings().stream()
                .anyMatch(warning -> warning.contains("base class changed")));
    }

    @Test
    void rejectsCandidateThatRemovesAnEstablishedScene() {
        String original = manimCode("Scene", "        self.wait(1)", "        self.wait(1)");
        String candidate = String.join("\n",
                "from manim import *",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.scene_1()",
                "    def scene_1(self):",
                "        self.wait(1)");

        CodeFixAcceptanceValidator.Decision decision = CodeFixAcceptanceValidator.evaluate(
                original, candidate, "manim", CodeFixSource.CODE_RENDER);

        assertFalse(decision.isAccepted());
        assertTrue(decision.summarizeIssues().contains("scene_2"));
    }

    @Test
    void rejectsOnlyWhenLargeSizeAndBehaviorBothCollapse() {
        StringBuilder original = new StringBuilder(String.join("\n",
                "from manim import *",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.scene_1()",
                "    def scene_1(self):"));
        for (int i = 0; i < 100; i++) {
            original.append("\n        self.play(Dot().animate.shift(RIGHT))  # beat ").append(i);
        }
        String candidate = String.join("\n",
                "from manim import *",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.scene_1()",
                "    def scene_1(self):",
                "        self.play(Write(Text(\"演示\")))",
                "        self.wait(1)");

        CodeFixAcceptanceValidator.Decision decision = CodeFixAcceptanceValidator.evaluate(
                original.toString(), candidate, "manim", CodeFixSource.CODE_RENDER);

        assertFalse(decision.isAccepted());
        assertTrue(decision.summarizeIssues().contains("substantially reduced behavior set"));
    }

    private static String manimCode(String baseClass, String scene1Body, String scene2Body) {
        return String.join("\n",
                "from manim import *",
                "from manim_voiceover import VoiceoverScene",
                "class MainScene(" + baseClass + "):",
                "    def construct(self):",
                "        dot = Dot()",
                "        self.scene_1()",
                "        self.scene_2()",
                "    def scene_1(self):",
                scene1Body,
                "    def scene_2(self):",
                scene2Body);
    }
}
