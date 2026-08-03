package com.kwang.study.mathvision.engine.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.fs.config.FSConfig;
import com.kwang.study.fs.dto.result.MimeTypeIdResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionContext;
import com.kwang.study.mathvision.engine.MathVisionStageExecutionResult;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.MathVisionArtifact;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.pojo.MathVisionVersion;
import com.kwang.study.mathvision.service.MathVisionFinalArtifactStorageService;
import com.kwang.study.mathvision.service.MathVisionFinalCodeArtifactService;
import com.kwang.study.mathvision.workflow.model.AiMessage;
import com.kwang.study.mathvision.workflow.model.CodeFixRequest;
import com.kwang.study.mathvision.workflow.model.CodeFixResult;
import com.kwang.study.mathvision.workflow.model.CodeResult;
import com.kwang.study.mathvision.workflow.model.Narrative;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.RenderResult;
import com.kwang.study.mathvision.workflow.model.SceneEvaluationResult;
import com.kwang.study.mathvision.workflow.node.CodeFixNode;
import com.kwang.study.mathvision.workflow.node.RenderNode;
import com.kwang.study.mathvision.workflow.node.SceneEvaluationNode;
import com.kwang.study.mathvision.workflow.prompt.StoryboardJsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RenderResultStageExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void succeedsAndStoresVideoWhenSceneFixBudgetIsExhausted() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MathVisionArtifactMapper artifactMapper = mock(MathVisionArtifactMapper.class);
        MathVisionVersionMapper versionMapper = mock(MathVisionVersionMapper.class);
        RenderNode renderNode = mock(RenderNode.class);
        SceneEvaluationNode sceneEvaluationNode = mock(SceneEvaluationNode.class);
        CodeFixNode codeFixNode = mock(CodeFixNode.class);
        MathVisionFinalCodeArtifactService finalCodeArtifactService = mock(MathVisionFinalCodeArtifactService.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        when(fileStorageService.getMimeTypeId(anyString())).thenReturn(supportedMime());

        FSConfig fsConfig = new FSConfig();
        fsConfig.setChunkSize(1024);
        MathVisionFinalArtifactStorageService storageService =
                new MathVisionFinalArtifactStorageService(fileStorageService, fsConfig);

        MathVisionTask task = MathVisionTask.builder()
                .id(42L)
                .currentVersion(3)
                .build();
        MathVisionVersion version = MathVisionVersion.builder()
                .taskId(42L)
                .version(3)
                .pnVersion(1)
                .vsVersion(1)
                .cgVersion(1)
                .build();
        when(versionMapper.findCurrent(42L)).thenReturn(version);

        ProblemBundle bundle = new ProblemBundle();
        Narrative narrative = narrativeWithStoryboard();
        CodeResult codeResult = new CodeResult();
        codeResult.setGeneratedCode("from manim import *\nclass MainScene(Scene):\n    def construct(self):\n        pass\n");
        codeResult.setSceneName("MainScene");
        codeResult.setOutputTarget("manim");
        when(artifactMapper.findByTaskStageVersion(eq(42L), anyString(), eq(1)))
                .thenAnswer(invocation -> artifactForStage(
                        invocation.getArgument(1), bundle, narrative, codeResult, objectMapper));

        Path localVideo = tempDir.resolve("MainScene.mp4");
        Files.writeString(localVideo, "video-content");
        RenderResult renderResult = new RenderResult();
        renderResult.setSuccess(true);
        renderResult.setSceneName("MainScene");
        renderResult.setArtifactType("mp4");
        renderResult.setArtifactPath(localVideo.toString());
        renderResult.setVideoPath(localVideo.toString());
        renderResult.setAttempts(1);
        RenderNode.Result renderNodeResult = renderNodeResult(renderResult, 0);
        when(renderNode.run(eq(task), any(CodeResult.class), eq(narrative),
                any(RenderNode.RenderRetryState.class), anyString(), anyInt(), any(Path.class),
                any(MathVisionStageExecutionContext.class))).thenReturn(renderNodeResult);

        SceneEvaluationResult sceneResult = new SceneEvaluationResult();
        sceneResult.setEvaluated(true);
        sceneResult.setApproved(false);
        sceneResult.setBlockingIssueCount(6);
        sceneResult.setTotalIssueCount(6);
        sceneResult.setGateReason("Detected blocking layout issues; routing to code fix");
        SceneEvaluationNode.Result sceneNodeResult = sceneEvaluationNodeResult(sceneResult, 0);
        when(sceneEvaluationNode.run(eq(task), eq(narrative), any(CodeResult.class),
                eq(renderResult), anyInt(), any(MathVisionStageExecutionContext.class)))
                .thenReturn(sceneNodeResult);
        when(sceneEvaluationNode.buildIssueSummaryForFix(sceneResult)).thenReturn("layout issues");
        when(sceneEvaluationNode.buildFixReportJsonForFix(sceneResult, narrative)).thenReturn("{}");

        CodeFixResult fixResult = new CodeFixResult();
        fixResult.setOutcome(CodeFixResult.FixOutcome.FIXED);
        fixResult.setApplied(true);
        fixResult.setFixedGeneratedCode(codeResult.getGeneratedCode());
        CodeFixNode.Result codeFixNodeResult = codeFixNodeResult(fixResult, 0);
        when(codeFixNode.run(eq(task), any(), any(MathVisionStageExecutionContext.class), anyList()))
                .thenReturn(codeFixNodeResult);

        MathVisionModelCatalog catalog = new MathVisionModelCatalog();
        catalog.getWorkflow().setRenderQuality("medium");
        catalog.getWorkflow().setRenderMaxRetries(7);
        catalog.getWorkflow().setSceneEvaluationMaxRetries(2);
        RenderResultStageExecutor executor = new RenderResultStageExecutor(
                artifactMapper,
                versionMapper,
                objectMapper,
                renderNode,
                sceneEvaluationNode,
                codeFixNode,
                finalCodeArtifactService,
                storageService,
                catalog,
                tempDir.resolve("runs").toString());
        Path renderWorkspace = tempDir.resolve("runs/task-42/v3/render");
        Path intermediateFile = renderWorkspace.resolve("media/partial/temporary.bin");
        Files.createDirectories(intermediateFile.getParent());
        Files.writeString(intermediateFile, "temporary-render-data");

        MathVisionStageExecutionResult result = executor.execute(
                MathVisionStageExecutionContext.builder()
                        .task(task)
                        .stage(StageEnum.RENDER_RESULT)
                        .build());

        assertFalse(result.isFailed());
        assertEquals("/mathvision/task-42/v3/final/final.mp4", result.getFinalArtifactPath());
        assertFalse(Files.exists(localVideo));
        assertFalse(Files.exists(renderWorkspace));
        assertTrue(sceneResult.getGateReason().contains("after 2 fix attempts"));

        JsonNode resultJson = objectMapper.readTree(result.getResultJson());
        assertTrue(resultJson.path("success").asBoolean());
        assertTrue(resultJson.path("renderSuccess").asBoolean());
        assertFalse(resultJson.path("sceneEvaluationApproved").asBoolean());
        assertTrue(resultJson.path("sceneEvaluationWarning").asBoolean());
        assertEquals("medium", resultJson.path("renderQuality").asText());
        assertEquals(7, resultJson.path("renderMaxRetries").asInt());
        assertEquals(2, resultJson.path("sceneEvaluationMaxRetries").asInt());
        assertEquals("/mathvision/task-42/v3/final/final.mp4",
                resultJson.path("artifactPath").asText());

        verify(renderNode, times(3)).run(eq(task), any(CodeResult.class), eq(narrative),
                any(RenderNode.RenderRetryState.class), anyString(), anyInt(), any(Path.class),
                any(MathVisionStageExecutionContext.class));
        verify(sceneEvaluationNode, times(3)).run(eq(task), eq(narrative), any(CodeResult.class),
                eq(renderResult), anyInt(), any(MathVisionStageExecutionContext.class));
        ArgumentCaptor<CodeFixRequest> requestCaptor = ArgumentCaptor.forClass(CodeFixRequest.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<AiMessage>> conversationCaptor =
                (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(codeFixNode, times(2)).run(
                eq(task), requestCaptor.capture(), any(MathVisionStageExecutionContext.class),
                conversationCaptor.capture());
        assertTrue(conversationCaptor.getAllValues().stream().allMatch(List::isEmpty));
        List<CodeFixRequest> requests = requestCaptor.getAllValues();
        assertTrue(requests.get(0).getFixHistory().isEmpty());
        assertEquals(List.of("layout issues"), requests.get(1).getFixHistory());
        assertEquals(StoryboardJsonBuilder.buildForSceneEvaluationFix(
                narrative.getStoryboard(), "manim"), requests.get(0).getStoryboardJson());
        assertEquals(0, resultJson.path("sceneFixConversationMessages").asInt());
        assertEquals(2, resultJson.path("sceneFixHistoryEntries").asInt());
        verify(fileStorageService).updateFileObject(
                org.mockito.ArgumentMatchers.startsWith("/mathvision/task-42/v3/final/upload-"),
                eq("final.mp4"), isNull(), isNull());
        verify(fileStorageService).createFile(
                org.mockito.ArgumentMatchers.startsWith("/mathvision/task-42/v3/final/upload-"),
                any(InputStream.class), eq("video/mp4"));
    }

    @Test
    void retainsSuccessfulVideoWhenLaterSceneFixRenderFails() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MathVisionArtifactMapper artifactMapper = mock(MathVisionArtifactMapper.class);
        MathVisionVersionMapper versionMapper = mock(MathVisionVersionMapper.class);
        RenderNode renderNode = mock(RenderNode.class);
        SceneEvaluationNode sceneEvaluationNode = mock(SceneEvaluationNode.class);
        CodeFixNode codeFixNode = mock(CodeFixNode.class);
        MathVisionFinalCodeArtifactService finalCodeArtifactService = mock(MathVisionFinalCodeArtifactService.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        when(fileStorageService.getMimeTypeId(anyString())).thenReturn(supportedMime());
        AtomicReference<String> uploadedVideo = new AtomicReference<>();
        doAnswer(invocation -> {
            InputStream input = invocation.getArgument(1);
            uploadedVideo.set(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return null;
        }).when(fileStorageService).createFile(anyString(), any(InputStream.class), eq("video/mp4"));

        FSConfig fsConfig = new FSConfig();
        fsConfig.setChunkSize(1024);
        MathVisionFinalArtifactStorageService storageService =
                new MathVisionFinalArtifactStorageService(fileStorageService, fsConfig);

        MathVisionTask task = MathVisionTask.builder()
                .id(43L)
                .currentVersion(4)
                .build();
        MathVisionVersion version = MathVisionVersion.builder()
                .taskId(43L)
                .version(4)
                .pnVersion(1)
                .vsVersion(1)
                .cgVersion(1)
                .build();
        when(versionMapper.findCurrent(43L)).thenReturn(version);

        ProblemBundle bundle = new ProblemBundle();
        Narrative narrative = new Narrative();
        CodeResult codeResult = new CodeResult();
        String successfulCode = "from manim import *\nclass MainScene(Scene):\n    def construct(self):\n        pass\n";
        codeResult.setGeneratedCode(successfulCode);
        codeResult.setSceneName("MainScene");
        codeResult.setOutputTarget("manim");
        when(artifactMapper.findByTaskStageVersion(eq(43L), anyString(), eq(1)))
                .thenAnswer(invocation -> artifactForStage(
                        invocation.getArgument(1), bundle, narrative, codeResult, objectMapper));

        Path localVideo = tempDir.resolve("retained-MainScene.mp4");
        Files.writeString(localVideo, "successful-video");
        RenderResult successfulRender = new RenderResult();
        successfulRender.setSuccess(true);
        successfulRender.setSceneName("MainScene");
        successfulRender.setOutputTarget("manim");
        successfulRender.setArtifactType("mp4");
        successfulRender.setArtifactPath(localVideo.toString());
        successfulRender.setVideoPath(localVideo.toString());
        successfulRender.setFinalGeneratedCode(successfulCode);
        successfulRender.setAttempts(1);

        RenderResult failedRender = new RenderResult();
        failedRender.setSuccess(false);
        failedRender.setSceneName("MainScene");
        failedRender.setOutputTarget("manim");
        failedRender.setArtifactType("mp4");
        failedRender.setFinalGeneratedCode("broken layout fix code");
        failedRender.setAttempts(2);
        failedRender.setLastError("NameError: broken layout fix");
        when(renderNode.run(eq(task), any(CodeResult.class), eq(narrative),
                any(RenderNode.RenderRetryState.class), anyString(), anyInt(), any(Path.class),
                any(MathVisionStageExecutionContext.class)))
                .thenReturn(renderNodeResult(successfulRender, 0), renderNodeResult(failedRender, 0));

        SceneEvaluationResult sceneResult = new SceneEvaluationResult();
        sceneResult.setEvaluated(true);
        sceneResult.setApproved(false);
        sceneResult.setBlockingIssueCount(1);
        sceneResult.setTotalIssueCount(1);
        sceneResult.setGateReason("layout issue");
        when(sceneEvaluationNode.run(eq(task), eq(narrative), any(CodeResult.class),
                eq(successfulRender), eq(0), any(MathVisionStageExecutionContext.class)))
                .thenReturn(sceneEvaluationNodeResult(sceneResult, 0));
        when(sceneEvaluationNode.buildIssueSummaryForFix(sceneResult)).thenReturn("layout issue");
        when(sceneEvaluationNode.buildFixReportJsonForFix(sceneResult, narrative)).thenReturn("{}");

        CodeFixResult fixResult = new CodeFixResult();
        fixResult.setOutcome(CodeFixResult.FixOutcome.FIXED);
        fixResult.setApplied(true);
        fixResult.setFixedGeneratedCode("broken layout fix code");
        when(codeFixNode.run(eq(task), any(), any(MathVisionStageExecutionContext.class), anyList()))
                .thenReturn(codeFixNodeResult(fixResult, 0));

        RenderResultStageExecutor executor = new RenderResultStageExecutor(
                artifactMapper,
                versionMapper,
                objectMapper,
                renderNode,
                sceneEvaluationNode,
                codeFixNode,
                finalCodeArtifactService,
                storageService,
                new MathVisionModelCatalog(),
                tempDir.resolve("runs").toString());

        MathVisionStageExecutionResult result = executor.execute(
                MathVisionStageExecutionContext.builder()
                        .task(task)
                        .stage(StageEnum.RENDER_RESULT)
                        .build());

        assertFalse(result.isFailed());
        assertEquals("/mathvision/task-43/v4/final/final.mp4", result.getFinalArtifactPath());
        JsonNode resultJson = objectMapper.readTree(result.getResultJson());
        assertTrue(resultJson.path("renderEverSucceeded").asBoolean());
        assertFalse(resultJson.path("renderFinalSuccess").asBoolean());
        assertTrue(resultJson.path("renderResult").path("success").asBoolean());
        assertFalse(resultJson.path("lastRenderResult").path("success").asBoolean());
        assertEquals("successful-video", uploadedVideo.get());
        verify(finalCodeArtifactService).persistFinalCode(task, successfulRender);
        verify(renderNode, times(2)).run(eq(task), any(CodeResult.class), eq(narrative),
                any(RenderNode.RenderRetryState.class), anyString(), anyInt(), any(Path.class),
                any(MathVisionStageExecutionContext.class));
    }

    @Test
    void keepsRenderSuccessfulWhenPlatformStorageArchiveFails() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MathVisionArtifactMapper artifactMapper = mock(MathVisionArtifactMapper.class);
        MathVisionVersionMapper versionMapper = mock(MathVisionVersionMapper.class);
        RenderNode renderNode = mock(RenderNode.class);
        SceneEvaluationNode sceneEvaluationNode = mock(SceneEvaluationNode.class);
        CodeFixNode codeFixNode = mock(CodeFixNode.class);
        MathVisionFinalCodeArtifactService finalCodeArtifactService = mock(MathVisionFinalCodeArtifactService.class);
        MathVisionFinalArtifactStorageService storageService = mock(MathVisionFinalArtifactStorageService.class);

        MathVisionTask task = MathVisionTask.builder().id(44L).currentVersion(5).build();
        MathVisionVersion version = MathVisionVersion.builder()
                .taskId(44L).version(5).pnVersion(1).vsVersion(1).cgVersion(1).build();
        when(versionMapper.findCurrent(44L)).thenReturn(version);

        ProblemBundle bundle = new ProblemBundle();
        Narrative narrative = new Narrative();
        CodeResult codeResult = new CodeResult();
        codeResult.setGeneratedCode("from manim import *\nclass MainScene(Scene):\n    def construct(self):\n        pass\n");
        codeResult.setSceneName("MainScene");
        codeResult.setOutputTarget("manim");
        when(artifactMapper.findByTaskStageVersion(eq(44L), anyString(), eq(1)))
                .thenAnswer(invocation -> artifactForStage(
                        invocation.getArgument(1), bundle, narrative, codeResult, objectMapper));

        Path localVideo = tempDir.resolve("storage-warning.mp4");
        Files.writeString(localVideo, "rendered-video");
        RenderResult renderResult = new RenderResult();
        renderResult.setSuccess(true);
        renderResult.setSceneName("MainScene");
        renderResult.setOutputTarget("manim");
        renderResult.setArtifactType("mp4");
        renderResult.setArtifactPath(localVideo.toString());
        renderResult.setVideoPath(localVideo.toString());
        renderResult.setFinalGeneratedCode(codeResult.getGeneratedCode());
        renderResult.setAttempts(1);
        when(renderNode.run(eq(task), any(CodeResult.class), eq(narrative),
                any(RenderNode.RenderRetryState.class), anyString(), anyInt(), any(Path.class),
                any(MathVisionStageExecutionContext.class)))
                .thenReturn(renderNodeResult(renderResult, 0));

        SceneEvaluationResult sceneResult = new SceneEvaluationResult();
        sceneResult.setEvaluated(true);
        sceneResult.setApproved(true);
        sceneResult.setGateReason("passed");
        when(sceneEvaluationNode.run(eq(task), eq(narrative), any(CodeResult.class),
                eq(renderResult), eq(0), any(MathVisionStageExecutionContext.class)))
                .thenReturn(sceneEvaluationNodeResult(sceneResult, 0));
        when(storageService.store(eq(task), eq(renderResult)))
                .thenThrow(new IOException("archive unavailable"));

        RenderResultStageExecutor executor = new RenderResultStageExecutor(
                artifactMapper, versionMapper, objectMapper, renderNode, sceneEvaluationNode, codeFixNode,
                finalCodeArtifactService, storageService, new MathVisionModelCatalog(),
                tempDir.resolve("runs").toString());
        Path renderWorkspace = tempDir.resolve("runs/task-44/v5/render");
        Path intermediateFile = renderWorkspace.resolve("07_geogebra_validation.json");
        Files.createDirectories(renderWorkspace);
        Files.writeString(intermediateFile, "temporary-validation-data");

        MathVisionStageExecutionResult result = executor.execute(
                MathVisionStageExecutionContext.builder()
                        .task(task)
                        .stage(StageEnum.RENDER_RESULT)
                        .build());

        assertFalse(result.isFailed());
        assertTrue(String.valueOf(result.getFinalArtifactPath()).endsWith("retained\\final.mp4")
                || String.valueOf(result.getFinalArtifactPath()).endsWith("retained/final.mp4"));
        assertTrue(Files.isRegularFile(Path.of(result.getFinalArtifactPath())));
        assertFalse(Files.exists(intermediateFile));
        try (java.util.stream.Stream<Path> files = Files.walk(renderWorkspace)) {
            assertEquals(1L, files.filter(Files::isRegularFile).count());
        }
        JsonNode resultJson = objectMapper.readTree(result.getResultJson());
        assertTrue(resultJson.path("success").asBoolean());
        assertEquals("archive unavailable", resultJson.path("artifactStorageWarning").asText());
    }

    @Test
    void preservesFullConversationAcrossConsecutiveRenderFixes() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MathVisionArtifactMapper artifactMapper = mock(MathVisionArtifactMapper.class);
        MathVisionVersionMapper versionMapper = mock(MathVisionVersionMapper.class);
        RenderNode renderNode = mock(RenderNode.class);
        SceneEvaluationNode sceneEvaluationNode = mock(SceneEvaluationNode.class);
        CodeFixNode codeFixNode = mock(CodeFixNode.class);
        MathVisionFinalCodeArtifactService finalCodeArtifactService = mock(MathVisionFinalCodeArtifactService.class);
        MathVisionFinalArtifactStorageService storageService = mock(MathVisionFinalArtifactStorageService.class);

        MathVisionTask task = MathVisionTask.builder()
                .id(44L)
                .currentVersion(1)
                .outputTarget("manim")
                .build();
        MathVisionVersion version = MathVisionVersion.builder()
                .taskId(44L).version(1).pnVersion(1).vsVersion(1).cgVersion(1).build();
        when(versionMapper.findCurrent(44L)).thenReturn(version);

        ProblemBundle bundle = new ProblemBundle();
        Narrative narrative = new Narrative();
        CodeResult codeResult = new CodeResult();
        codeResult.setGeneratedCode("from manim import *\nclass MainScene(Scene):\n    def construct(self):\n        pass\n");
        codeResult.setSceneName("MainScene");
        codeResult.setOutputTarget("manim");
        when(artifactMapper.findByTaskStageVersion(eq(44L), anyString(), eq(1)))
                .thenAnswer(invocation -> artifactForStage(
                        invocation.getArgument(1), bundle, narrative, codeResult, objectMapper));

        AtomicInteger renderAttempt = new AtomicInteger();
        doAnswer(invocation -> {
            int attempt = renderAttempt.incrementAndGet();
            Path renderWorkspace = invocation.getArgument(6);
            Path intermediate = renderWorkspace.resolve("media/attempt-" + attempt + "/partial.tmp");
            Files.createDirectories(intermediate.getParent());
            Files.writeString(intermediate, "failed-attempt");
            RenderNode.RenderRetryState state = invocation.getArgument(3);
            setRenderRetryState(state, attempt, "NameError: failure " + attempt);
            RenderResult failed = new RenderResult();
            failed.setSuccess(false);
            failed.setSceneName("MainScene");
            failed.setOutputTarget("manim");
            failed.setArtifactType("mp4");
            failed.setFinalGeneratedCode(codeResult.getGeneratedCode());
            failed.setAttempts(attempt);
            failed.setLastError("NameError: failure " + attempt);
            return renderNodeResult(failed, 0);
        }).when(renderNode).run(eq(task), any(CodeResult.class), eq(narrative),
                any(RenderNode.RenderRetryState.class), anyString(), anyInt(), any(Path.class),
                any(MathVisionStageExecutionContext.class));

        List<List<AiMessage>> conversationSnapshots = new ArrayList<>();
        AtomicInteger fixAttempt = new AtomicInteger();
        doAnswer(invocation -> {
            conversationSnapshots.add(new ArrayList<>(invocation.getArgument(3)));
            int attempt = fixAttempt.incrementAndGet();
            CodeFixResult fix = new CodeFixResult();
            fix.setCurrentRequestPrompt("render-fix-prompt-" + attempt);
            fix.setErrorReason("NameError: failure " + attempt);
            if (attempt == 1) {
                fix.setApplied(true);
                fix.setOutcome(CodeFixResult.FixOutcome.FIXED);
                fix.setFixedGeneratedCode(codeResult.getGeneratedCode() + "\n# fixed once");
            } else {
                fix.setApplied(false);
                fix.setOutcome(CodeFixResult.FixOutcome.FAILED);
                fix.setFailureReason("still failing");
            }
            return codeFixNodeResult(fix, 1, "assistant-render-fix-" + attempt);
        }).when(codeFixNode).run(eq(task), any(), any(MathVisionStageExecutionContext.class), anyList());

        MathVisionModelCatalog catalog = new MathVisionModelCatalog();
        catalog.getWorkflow().setRenderFixConversationRounds(10);
        RenderResultStageExecutor executor = new RenderResultStageExecutor(
                artifactMapper, versionMapper, objectMapper, renderNode, sceneEvaluationNode, codeFixNode,
                finalCodeArtifactService, storageService, catalog,
                tempDir.resolve("runs").toString());

        MathVisionStageExecutionResult result = executor.execute(
                MathVisionStageExecutionContext.builder().task(task).stage(StageEnum.RENDER_RESULT).build());

        assertTrue(result.isFailed());
        assertEquals(2, conversationSnapshots.size());
        assertEquals(0, conversationSnapshots.get(0).size());
        assertEquals(2, conversationSnapshots.get(1).size());
        assertEquals("user", conversationSnapshots.get(1).get(0).getRole());
        assertEquals("assistant", conversationSnapshots.get(1).get(1).getRole());
        JsonNode resultJson = objectMapper.readTree(result.getResultJson());
        assertEquals(4, resultJson.path("renderFixConversationMessages").asInt());
        assertFalse(Files.exists(tempDir.resolve("runs/task-44/v1/render")));
    }

    private static Narrative narrativeWithStoryboard() {
        Narrative.Storyboard storyboard = new Narrative.Storyboard();
        Narrative.StoryboardScene scene = new Narrative.StoryboardScene();
        scene.setSceneId("scene_1");
        scene.setTitle("Scene 1");
        scene.setGoal("Show the construction");
        scene.setLayoutGoal("Keep the diagram centered");
        storyboard.setScenes(List.of(scene));
        return new Narrative("Explain the construction", storyboard);
    }

    private static MathVisionArtifact artifactForStage(String stage,
                                                       ProblemBundle bundle,
                                                       Narrative narrative,
                                                       CodeResult codeResult,
                                                       ObjectMapper objectMapper) throws Exception {
        Object value;
        if (StageEnum.PROBLEM_NORMALIZATION.getCode().equals(stage)) {
            value = bundle;
        } else if (StageEnum.VISUAL_STORYBOARD.getCode().equals(stage)) {
            value = narrative;
        } else {
            value = codeResult;
        }
        return MathVisionArtifact.builder()
                .artifactJson(objectMapper.writeValueAsString(value))
                .build();
    }

    private static MimeTypeIdResult supportedMime() {
        MimeTypeIdResult result = new MimeTypeIdResult();
        result.setSuccess(Boolean.TRUE);
        result.setMimeTypeId(1);
        return result;
    }

    private static RenderNode.Result renderNodeResult(RenderResult result, int apiCalls) throws Exception {
        Constructor<RenderNode.Result> constructor =
                RenderNode.Result.class.getDeclaredConstructor(RenderResult.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(result, apiCalls);
    }

    private static SceneEvaluationNode.Result sceneEvaluationNodeResult(
            SceneEvaluationResult result, int apiCalls) throws Exception {
        Constructor<SceneEvaluationNode.Result> constructor =
                SceneEvaluationNode.Result.class.getDeclaredConstructor(SceneEvaluationResult.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(result, apiCalls);
    }

    private static CodeFixNode.Result codeFixNodeResult(CodeFixResult result, int apiCalls) throws Exception {
        Constructor<CodeFixNode.Result> constructor =
                CodeFixNode.Result.class.getDeclaredConstructor(CodeFixResult.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(result, apiCalls);
    }

    private static CodeFixNode.Result codeFixNodeResult(
            CodeFixResult result, int apiCalls, String assistantTranscript) throws Exception {
        Constructor<CodeFixNode.Result> constructor =
                CodeFixNode.Result.class.getDeclaredConstructor(CodeFixResult.class, int.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(result, apiCalls, assistantTranscript);
    }

    private static void setRenderRetryState(
            RenderNode.RenderRetryState state, int attempts, String error) throws Exception {
        setField(state, "attempts", attempts);
        setField(state, "requestFix", true);
        setField(state, "pendingFocusedError", error);
        setField(state, "pendingStaticAuditIssues", new ArrayList<String>());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
