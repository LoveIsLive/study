package com.kwang.study.mathvision.workflow.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ManimCodeUtilsTest {

    @Test
    void validateFullDetectsAnimateMethodAssignment() {
        String code = String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        dot = Dot()",
                "        self.play(dot.animate.set_color = \"#F59E0B\")");

        List<String> violations = ManimCodeUtils.validateFull(code);

        assertTrue(violations.stream().anyMatch(v -> v.contains("invalid Python syntax")
                && v.contains("animate.method")), () -> String.join("\n", violations));
    }

    @Test
    void validateFullDetectsRawStringTrailingBackslash() {
        String rawStringEndingWithSingleBackslash = "            r" + '"' + "\\" + '"' + ",";
        String code = String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        label = MathTex(",
                rawStringEndingWithSingleBackslash,
                "        )");

        List<String> violations = ManimCodeUtils.validateFull(code);

        assertTrue(violations.stream().anyMatch(v -> v.contains("invalid Python syntax")
                && v.contains("raw string literal cannot end")), () -> String.join("\n", violations));
    }

    @Test
    void validateFullAllowsNormalAnimateCallsAndRawStrings() {
        String code = String.join("\n",
                "from manim import *",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        dot = Dot()",
                "        label = MathTex(r\"\\theta\")",
                "        self.play(dot.animate.set_color(\"#F59E0B\"))");

        List<String> violations = ManimCodeUtils.validateFull(code);

        assertTrue(violations.stream().noneMatch(v -> v.contains("invalid Python syntax")),
                () -> String.join("\n", violations));
    }

}
