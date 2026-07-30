package com.kwang.study.mathvision.workflow.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManimRenderServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void renderTimeoutBudgetKeepsTenMinuteMinimumForSmallScene() {
        ManimRenderService.RenderTimeoutBudget budget = ManimRenderService.estimateRenderTimeout(
                "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n        self.wait(1)\n");

        assertEquals(10, budget.timeoutMinutes());
        assertEquals(1, budget.sceneCount());
    }

    @Test
    void renderTimeoutBudgetScalesWithCodeLinesAndSceneCount() {
        ManimRenderService.RenderTimeoutBudget budget = ManimRenderService.estimateRenderTimeout(
                buildLargeMultiSceneCode(7, 960));
        long expected = Math.max(10L, divideRoundUp(budget.codeLines(), 120) + budget.sceneCount() * 2L);

        assertEquals(7, budget.sceneCount());
        assertTrue(budget.codeLines() >= 960);
        assertEquals(expected, budget.timeoutMinutes());
        assertTrue(budget.timeoutMinutes() > 10);
    }

    @Test
    void voiceoverRenderDisablesManimCaching() {
        ManimRenderService service = new ManimRenderService();
        String voiceoverCode = String.join("\n",
                "from manim_voiceover import VoiceoverScene",
                "class MainScene(VoiceoverScene):",
                "    def construct(self):",
                "        with self.voiceover(text=\"中文旁白\") as tracker:",
                "            self.wait(tracker.duration)");

        assertTrue(ManimRenderService.containsVoiceoverCode(voiceoverCode));
        assertFalse(ManimRenderService.containsVoiceoverCode(
                "from manim import *\nclass MainScene(Scene):\n    pass"));

        List<String> voiceoverCommand = service.buildCommand(
                tempDir.resolve("scene_render.py"), "MainScene", "low", tempDir, true);
        List<String> silentCommand = service.buildCommand(
                tempDir.resolve("scene_render.py"), "MainScene", "low", tempDir, false);

        assertTrue(voiceoverCommand.contains("--disable_caching"));
        assertFalse(silentCommand.contains("--disable_caching"));
    }

    @Test
    void fatalTracebackOnStderrTerminatesRenderBeforeTimeout() {
        BlockingProcess process = new BlockingProcess(String.join("\n",
                "Animation 7: FadeIn(Text('AQ')):   0%|          | 0/12 [00:00<?, ?it/s]",
                "Traceback (most recent call last):",
                "  File \"scene_render.py\", line 114, in scene_2",
                "ValueError: zip() argument 2 is longer than argument 1"));

        ManimRenderService service = new ManimRenderService() {
            @Override
            protected Process startProcess(List<String> command, Path outputDir, Path geometryOutputFile) {
                return process;
            }
        };

        long startedAt = System.nanoTime();
        ManimRenderService.Attempt result = service.render(
                "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n        pass\n",
                "MainScene",
                "low",
                tempDir
        );
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertFalse(result.isSuccess());
        assertFalse(result.isTimedOut());
        assertTrue(process.destroyed);
        assertTrue(elapsedMillis < 2_000, "fatal stderr should not wait for the render timeout");
        assertTrue(result.getStderr().contains("ValueError: zip() argument 2 is longer than argument 1"));
        assertTrue(result.getStderr().contains("Fatal Manim traceback detected in stderr"));
    }

    @Test
    void fatalTracebackIgnoresManimControlExceptionContextLines() {
        BlockingProcess process = new BlockingProcess(String.join("\n",
                "Traceback (most recent call last):",
                "|   260 |       except EndSceneEarlyException:                               |",
                "|   262 |       except RerunSceneException:                                  |",
                "| D:\\project\\study\\mathvision-runs\\task-2\\v2\\render\\scene_render.py:231 in scene_1 |",
                "| > 231 |       label_A = Text(\"A\", font_weight=\"bold\")                  |",
                "TypeError: Mobject.__init__() got an unexpected keyword argument 'font_weight'"));

        ManimRenderService service = new ManimRenderService() {
            @Override
            protected Process startProcess(List<String> command, Path outputDir, Path geometryOutputFile) {
                return process;
            }
        };

        ManimRenderService.Attempt result = service.render(
                "from manim import *\n\nclass MainScene(Scene):\n    def construct(self):\n        pass\n",
                "MainScene",
                "low",
                tempDir
        );

        assertFalse(result.isSuccess());
        assertTrue(process.destroyed);
        assertTrue(result.getStderr().contains(
                "Fatal Manim traceback detected in stderr: TypeError: Mobject.__init__() got an unexpected keyword argument 'font_weight'"));
        assertFalse(result.getStderr().contains(
                "Fatal Manim traceback detected in stderr: EndSceneEarlyException"));
    }

    private String buildLargeMultiSceneCode(int sceneCount, int fillerLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("from manim import *\n\n");
        sb.append("class MainScene(Scene):\n");
        sb.append("    def construct(self):\n");
        for (int i = 1; i <= sceneCount; i++) {
            sb.append("        self.scene_").append(i).append("()\n");
        }
        for (int i = 1; i <= sceneCount; i++) {
            sb.append("\n");
            sb.append("    def scene_").append(i).append("(self):\n");
            sb.append("        self.wait(0.1)\n");
        }
        for (int i = 0; i < fillerLines; i++) {
            sb.append("        # filler line ").append(i).append("\n");
        }
        return sb.toString();
    }

    private long divideRoundUp(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static final class BlockingProcess extends Process {
        private final InputStream stdout = new ByteArrayInputStream(new byte[0]);
        private final InputStream stderr;
        private final CountDownLatch destroyedLatch = new CountDownLatch(1);
        private volatile boolean destroyed;

        private BlockingProcess(String stderr) {
            this.stderr = new ByteArrayInputStream(stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            destroyedLatch.await();
            return 1;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return destroyedLatch.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (!destroyed) {
                throw new IllegalThreadStateException("process still running");
            }
            return 1;
        }

        @Override
        public void destroy() {
            destroyed = true;
            destroyedLatch.countDown();
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }
    }
}
