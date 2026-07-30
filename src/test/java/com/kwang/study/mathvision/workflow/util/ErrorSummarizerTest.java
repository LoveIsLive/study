package com.kwang.study.mathvision.workflow.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorSummarizerTest {

    @Test
    void buildDisplayErrorPrioritizesStderrTracebackOverStdoutCacheLogs() {
        String stdout = String.join("\n",
                "Manim Community v0.20.1",
                "[07/21/26 15:41:26] INFO     Animation 0 : Using cached    cairo_renderer.py:94");
        String stderr = String.join("\n",
                "Traceback (most recent call last):",
                "  File \"scene_render.py\", line 231, in scene_1",
                "    label_A = Text(\"A\", font_weight=\"bold\")",
                "TypeError: Mobject.__init__() got an unexpected keyword argument 'font_weight'");

        String displayError = ErrorSummarizer.buildDisplayError(stdout, stderr);

        assertTrue(displayError.startsWith("[stderr]"));
        assertTrue(displayError.contains("scene_render.py"));
        assertTrue(displayError.contains("font_weight"));
        assertFalse(displayError.contains("Using cached"));
    }
}
