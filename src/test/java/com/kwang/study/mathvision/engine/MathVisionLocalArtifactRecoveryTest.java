package com.kwang.study.mathvision.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.model.RenderResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class MathVisionLocalArtifactRecoveryTest {
    @TempDir Path root;
    @Test void newExecutionCanReuseVerifiedLocalRenderWithoutDependingOnOldWorkspace() throws Exception {
        var json = new ObjectMapper(); var recovery = new MathVisionLocalArtifactRecovery(json, root.toString());
        Path old = root.resolve("task-1/v1/run-old/render"); Files.createDirectories(old);
        Path video = old.resolve("final.mp4"); Files.writeString(video, "rendered-video");
        var rendered = new RenderResult(); rendered.setSuccess(true); rendered.setArtifactType("mp4"); rendered.setArtifactPath(video.toString());
        recovery.checkpoint(old, "same-input", rendered, json.createObjectNode().putArray("samples"));
        Path next = root.resolve("task-1/v2/run-new/render");
        var task = MathVisionTask.builder().id(1L).currentVersion(2).build();
        var restored = recovery.restore(task, next, "same-input"); assertNotNull(restored);
        Path copied = Path.of(restored.render.getArtifactPath()); assertTrue(copied.startsWith(next));
        assertEquals("rendered-video", Files.readString(copied)); assertTrue(Files.exists(video));
        assertNull(recovery.restore(task, next, "different-input"));
        Files.writeString(video, "corrupted"); assertNull(recovery.restore(task, next, "same-input"));
        assertNull(recovery.restore(MathVisionTask.builder().id(2L).build(), next, "same-input"));
    }
}
