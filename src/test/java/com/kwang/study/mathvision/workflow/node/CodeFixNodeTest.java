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

    @Test
    void migratesLegacyGttsInFixedManimCode() {
        String legacyCode = String.join("\n",
                "from manim import *",
                "from manim_voiceover import VoiceoverScene",
                "from manim_voiceover.services.gtts import GTTSService",
                "class MainScene(VoiceoverScene):",
                "    def construct(self):",
                "        self.set_speech_service(GTTSService(lang=\"zh-CN\", global_speed=1.5))",
                "        self.wait(1)");
        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.CODE_RENDER);
        request.setOutputTarget("manim");
        request.setGeneratedCode(legacyCode);
        request.setErrorReason("Voice synthesis failed");

        MathVisionTask task = new MathVisionTask();
        task.setId(9L);

        CodeFixNode.Result result = new CodeFixNode(new FakeAiChatService(legacyCode))
                .run(task, request, null);

        assertTrue(result.getFixResult().isApplied());
        assertTrue(result.getFixResult().getFixedGeneratedCode()
                .contains("from mathvision_edge_tts import EdgeTTSService"));
        assertFalse(result.getFixResult().getFixedGeneratedCode().contains("GTTSService"));
    }

    @Test
    void rejectsCandidateThatDropsGeneratedScenesWithoutApplyingIt() {
        String original = String.join("\n",
                "from manim import *",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.scene_1()",
                "        self.scene_2()",
                "    def scene_1(self):",
                "        self.play(Write(Text(\"第一场景\")))",
                "    def scene_2(self):",
                "        self.play(Write(Text(\"第二场景\")))");
        String shortened = String.join("\n",
                "from manim import *",
                "class MainScene(Scene):",
                "    def construct(self):",
                "        self.play(Write(Text(\"演示\")))");
        CodeFixRequest request = new CodeFixRequest();
        request.setSource(CodeFixSource.CODE_RENDER);
        request.setOutputTarget("manim");
        request.setGeneratedCode(original);
        request.setErrorReason("Render failed");

        MathVisionTask task = new MathVisionTask();
        task.setId(10L);
        CodeFixNode.Result result = new CodeFixNode(new FakeAiChatService(shortened))
                .run(task, request, null);

        assertFalse(result.getFixResult().isApplied());
        assertEquals(CodeFixResult.FixOutcome.REJECTED_CONTRACT, result.getFixResult().getOutcome());
        assertTrue(result.getFixResult().getFailureReason().contains("scene_1"));
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
