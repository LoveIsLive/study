package com.kwang.study.mathvision.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.ai.MathVisionAiChatService;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.model.CodeFixRequest;
import com.kwang.study.mathvision.workflow.model.CodeFixResult;
import com.kwang.study.mathvision.workflow.model.CodeFixSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeFixNodeTest {

    @Test
    void rejectsManimFixThatStillViolatesCoordinateScaleContract() {
        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.CODE_RENDER);
        request.setOutputTarget("manim");
        request.setGeneratedCode("from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n        self.wait(1)\n");
        request.setErrorReason("Render preflight failed");

        MathVisionTask task = new MathVisionTask();
        task.setId(7L);

        CodeFixNode.Result result = new CodeFixNode(new FakeAiChatService(coordinateBrokenCode()))
                .run(task, request, null);

        assertFalse(result.getFixResult().isApplied());
        assertEquals(CodeFixResult.FixOutcome.FAILED, result.getFixResult().getOutcome());
        assertTrue(result.getFixResult().getFailureReason().contains("coordinate scale contract"));
    }

    @Test
    void addsDefaultGeoGebraViewCommandToFixedScript() {
        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.CODE_RENDER);
        request.setOutputTarget("geogebra");
        request.setGeneratedCode("A = (0, 0)");
        request.setErrorReason("Viewport is not initialized");

        MathVisionTask task = new MathVisionTask();
        task.setId(8L);

        CodeFixNode.Result result = new CodeFixNode(new FakeAiChatService("A = (1, 1)"))
                .run(task, request, null);

        assertTrue(result.getFixResult().isApplied());
        assertTrue(result.getFixResult().getFixedGeneratedCode().startsWith("SetCoordSystem("));
        assertTrue(result.getFixResult().getFixedGeneratedCode().contains("A = (1, 1)"));
    }

    private static String coordinateBrokenCode() {
        return String.join("\n",
                "from manim import *",
                "import numpy as np",
                "",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.objects = {}",
                "        self.setup_shared_scene()",
                "",
                "    def setup_shared_scene(self):",
                "        self._mv_x_range = [-1.0, 1.0, 1.0]",
                "        self._mv_y_range = [-1.0, 1.0, 1.0]",
                "        self._mv_axes = None",
                "        self.axes = Axes(x_range=self._mv_x_range, y_range=self._mv_y_range, x_length=6.0, y_length=4.0)",
                "        self._mv_axes = self.axes",
                "",
                "    def world_point(self, x, y=0.0, z=0.0):",
                "        return self._mv_axes.c2p(x, y)",
                "",
                "    def world_radius(self, radius):",
                "        return abs(radius)");
    }

    private static final class FakeAiChatService extends MathVisionAiChatService {
        private final String fixedCode;

        private FakeAiChatService(String fixedCode) {
            super(null, null, null, new ObjectMapper());
            this.fixedCode = fixedCode;
        }

        @Override
        public CodeResponse requestCode(MathVisionTask task,
                                        List<AiMessage> messages,
                                        String toolsJson,
                                        List<String> preferredFields) {
            return codeResponse(fixedCode);
        }

        private static CodeResponse codeResponse(String code) {
            try {
                Constructor<CodeResponse> constructor = CodeResponse.class.getDeclaredConstructor(
                        JsonNode.class, String.class, String.class, String.class, int.class);
                constructor.setAccessible(true);
                return constructor.newInstance(NullNode.getInstance(), code, code, "", 1);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }
}
