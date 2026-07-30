package com.kwang.study.mathvision.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.auth.custom.CustomUserDetails;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.llm.mapper.ChatMemoryMapper;
import com.kwang.study.llm.mapper.ChatSessionMapper;
import com.kwang.study.llm.pojo.ChatSession;
import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.config.MathVisionModelCatalog.ModelCatalog;
import com.kwang.study.mathvision.config.MathVisionModelCatalog.ProviderCatalog;
import com.kwang.study.mathvision.controller.MathVisionFileUploadController;
import com.kwang.study.mathvision.dto.MathVisionVersionDetailVO;
import com.kwang.study.mathvision.dto.MathVisionVersionItemVO;
import com.kwang.study.mathvision.dto.MathVisionTaskRuntimeSettingsRequestDTO;
import com.kwang.study.mathvision.dto.MathVisionTaskTitleUpdateRequestDTO;
import com.kwang.study.mathvision.dto.StageAutoEditRequestDTO;
import com.kwang.study.mathvision.dto.StageDataVO;
import com.kwang.study.mathvision.dto.StageOperationResultVO;
import com.kwang.study.mathvision.mapper.LlmModelConfigMapper;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionStageResultMapper;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.MathVisionArtifact;
import com.kwang.study.mathvision.pojo.LlmModelConfig;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.pojo.MathVisionVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MathVisionTaskVersionServiceTest {

    private static final Long USER_ID = 9L;

    @Mock private ChatSessionMapper chatSessionMapper;
    @Mock private ChatMemoryMapper chatMemoryMapper;
    @Mock private MathVisionTaskMapper taskMapper;
    @Mock private MathVisionVersionMapper versionMapper;
    @Mock private MathVisionArtifactMapper artifactMapper;
    @Mock private MathVisionStageResultMapper stageResultMapper;
    @Mock private LlmModelConfigMapper configMapper;
    @Mock private MathVisionModelCatalog catalog;
    @Mock private FileStorageService fileStorageService;
    @Mock private MathVisionFileUploadController uploadController;
    @Mock private MathVisionTaskNotifier taskNotifier;

    @TempDir Path renderOutputRoot;

    private MathVisionTaskService service;

    @BeforeEach
    void setUp() {
        CustomUserDetails principal = CustomUserDetails.builder()
                .id(USER_ID)
                .username("version-user")
                .authorities(Collections.emptyList())
                .enabled(true)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        service = new MathVisionTaskService(
                chatSessionMapper,
                chatMemoryMapper,
                taskMapper,
                versionMapper,
                artifactMapper,
                stageResultMapper,
                configMapper,
                catalog,
                fileStorageService,
                uploadController,
                taskNotifier,
                new ObjectMapper(),
                renderOutputRoot.toString());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsNewestVersionFirstAndMarksCurrentVersion() {
        MathVisionTask task = task(21L, "completed", "completed", 2);
        MathVisionVersion v2 = version(21L, 2, 1, 1, 1, 1, 1);
        MathVisionVersion v1 = version(21L, 1, 1, 1, null, null, null);
        when(taskMapper.findById(21L)).thenReturn(task);
        when(versionMapper.findByTask(21L)).thenReturn(List.of(v2, v1));
        when(artifactMapper.findByTaskStageVersion(21L, "render_result", 1))
                .thenReturn(artifact(21L, "render_result", 1,
                        "{\"success\":true,\"artifactPath\":\"/mathvision/task-21/v2.mp4\",\"artifactType\":\"mp4\"}"));

        List<MathVisionVersionItemVO> versions = service.listTaskVersions(21L);

        assertEquals(List.of(2, 1), versions.stream()
                .map(MathVisionVersionItemVO::getVersion)
                .collect(Collectors.toList()));
        assertTrue(versions.get(0).getIsCurrent());
        assertFalse(versions.get(1).getIsCurrent());
        assertEquals("render_result", versions.get(0).getLatestStage());
        assertEquals("mp4", versions.get(0).getFinalArtifactType());
    }

    @Test
    void returnsAllStagePointersAndReadableCodeInVersionDetail() {
        MathVisionTask task = task(22L, "waiting_confirm", "code_generation", 3);
        MathVisionVersion version = version(22L, 3, 1, 2, 4, 5, null);
        when(taskMapper.findById(22L)).thenReturn(task);
        when(versionMapper.findByTaskVersion(22L, 3)).thenReturn(version);
        when(artifactMapper.findByTaskStageVersion(22L, "problem_normalization", 1))
                .thenReturn(artifact(22L, "problem_normalization", 1, "{\"problem\":\"demo\"}"));
        when(artifactMapper.findByTaskStageVersion(22L, "reasoning_graph", 2))
                .thenReturn(artifact(22L, "reasoning_graph", 2, "{\"nodes\":[]}"));
        when(artifactMapper.findByTaskStageVersion(22L, "visual_storyboard", 4))
                .thenReturn(artifact(22L, "visual_storyboard", 4, "{\"scenes\":[]}"));
        when(artifactMapper.findByTaskStageVersion(22L, "code_generation", 5))
                .thenReturn(artifact(22L, "code_generation", 5,
                        "{\"generatedCode\":\"print('V3')\",\"artifactFormat\":\"python\"}"));

        MathVisionVersionDetailVO detail = service.getTaskVersionDetail(22L, 3);

        assertEquals(3, detail.getVersion());
        assertEquals(5, detail.getCodeGenerationVersion());
        assertEquals("print('V3')", detail.getCodeText());
        assertEquals("python", detail.getCodeFormat());
        assertEquals("code_generation", detail.getLatestStage());
    }

    @Test
    void rejectsVersionSwitchWhileTaskIsRunning() {
        when(taskMapper.findById(23L)).thenReturn(task(23L, "running", "reasoning_graph", 2));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.activateTaskVersion(23L, 1));

        assertEquals("任务正在执行或取消中，不能切换版本", error.getMessage());
        verify(versionMapper, never()).clearCurrent(23L);
    }

    @Test
    void activatesIntermediateVersionAtItsLatestStage() {
        MathVisionTask before = task(24L, "completed", "completed", 2);
        MathVisionTask after = task(24L, "waiting_confirm", "visual_storyboard", 1);
        after.setLastConfirmedStage("reasoning_graph");
        MathVisionVersion target = version(24L, 1, 1, 1, 1, null, null);
        when(taskMapper.findById(24L)).thenReturn(before, after);
        when(versionMapper.findByTaskVersion(24L, 1)).thenReturn(target);
        when(versionMapper.setCurrent(24L, 1)).thenReturn(1);
        when(taskMapper.activateVersionState(
                24L, USER_ID, 1, "waiting_confirm", "visual_storyboard", "reasoning_graph",
                null, null, null, null, null)).thenReturn(1);
        assertEquals("waiting_confirm", service.activateTaskVersion(24L, 1).getStatus());

        verify(versionMapper).clearCurrent(24L);
        verify(taskMapper).activateVersionState(
                24L, USER_ID, 1, "waiting_confirm", "visual_storyboard", "reasoning_graph",
                null, null, null, null, null);
        verify(taskNotifier).notifyTaskChanged(24L, "version_activated");
    }

    @Test
    void activatesCodeGeneratedVersionAtRenderStageEvenWhenReviewDidNotApprove() {
        MathVisionTask before = task(241L, "completed", "completed", 2);
        MathVisionTask after = task(241L, "waiting_confirm", "render_result", 1);
        after.setLastConfirmedStage("code_generation");
        MathVisionVersion target = version(241L, 1, 1, 1, 1, 3, null);
        when(taskMapper.findById(241L)).thenReturn(before, after);
        when(versionMapper.findByTaskVersion(241L, 1)).thenReturn(target);
        when(versionMapper.setCurrent(241L, 1)).thenReturn(1);
        when(taskMapper.activateVersionState(
                241L, USER_ID, 1, "waiting_confirm", "render_result", "code_generation",
                null, null, null, null, null)).thenReturn(1);
        assertEquals("render_result", service.activateTaskVersion(241L, 1).getCurrentStage());

        verify(taskMapper).activateVersionState(
                241L, USER_ID, 1, "waiting_confirm", "render_result", "code_generation",
                null, null, null, null, null);
    }

    @Test
    void activatesCompletedVersionAndRestoresFinalArtifact() {
        MathVisionTask before = task(25L, "failed", "render_result", 3);
        MathVisionTask after = task(25L, "completed", "completed", 2);
        after.setLastConfirmedStage("render_result");
        after.setFinalArtifactPath("/mathvision/task-25/v2.html");
        after.setFinalArtifactType("html");
        MathVisionVersion target = version(25L, 2, 1, 1, 1, 1, 4);
        when(taskMapper.findById(25L)).thenReturn(before, after);
        when(versionMapper.findByTaskVersion(25L, 2)).thenReturn(target);
        when(versionMapper.setCurrent(25L, 2)).thenReturn(1);
        when(artifactMapper.findByTaskStageVersion(25L, "render_result", 4))
                .thenReturn(artifact(25L, "render_result", 4,
                        "{\"success\":true,\"artifactPath\":\"/mathvision/task-25/v2.html\",\"artifactType\":\"html\"}"));
        when(taskMapper.activateVersionState(
                25L, USER_ID, 2, "completed", "completed", "render_result",
                null, null, null, "/mathvision/task-25/v2.html", "html")).thenReturn(1);
        assertEquals("/mathvision/task-25/v2.html",
                service.activateTaskVersion(25L, 2).getFinalArtifactPath());

        verify(taskMapper).activateVersionState(
                25L, USER_ID, 2, "completed", "completed", "render_result",
                null, null, null, "/mathvision/task-25/v2.html", "html");
    }

    @Test
    void exposesAutoEditCapabilityForIdleGeneratedStages() {
        MathVisionTask task = task(26L, "completed", "completed", 2);
        MathVisionVersion version = version(26L, 2, 1, 2, 3, 4, 5);
        when(taskMapper.findById(26L)).thenReturn(task);
        when(versionMapper.findCurrent(26L)).thenReturn(version);
        when(artifactMapper.findByTaskStageVersion(26L, "reasoning_graph", 2))
                .thenReturn(artifact(26L, "reasoning_graph", 2, "{\"nodes\":{}}"));

        StageDataVO stage = service.getStageData(26L, "reasoning_graph");

        assertTrue(stage.isCanAutoEdit());
        assertTrue(stage.isCanRegenerate());
        assertEquals(2, stage.getStageVersion());
        assertNull(stage.getResultJson());
        verifyNoInteractions(stageResultMapper);
    }

    @Test
    void exposesRegenerateCapabilityForCompletedUpstreamStageWhenTaskFailedLater() {
        MathVisionTask task = task(32L, "failed", "render_result", 2);
        task.setFailedStage("render_result");
        MathVisionVersion version = version(32L, 2, 1, 2, 3, 4, null);
        when(taskMapper.findById(32L)).thenReturn(task);
        when(versionMapper.findCurrent(32L)).thenReturn(version);
        when(artifactMapper.findByTaskStageVersion(32L, "reasoning_graph", 2))
                .thenReturn(artifact(32L, "reasoning_graph", 2, "{\"nodes\":{}}"));

        StageDataVO stage = service.getStageData(32L, "reasoning_graph");

        assertTrue(stage.isCanRegenerate());
        assertTrue(stage.isCanAutoEdit());
        assertEquals(2, stage.getStageVersion());
    }

    @Test
    void queuesAutoEditOnNewTaskVersionAndRetainsBaselinePointer() {
        MathVisionTask task = task(27L, "completed", "completed", 2);
        MathVisionVersion source = version(27L, 2, 1, 2, 3, 4, 5);
        StageAutoEditRequestDTO request = new StageAutoEditRequestDTO();
        request.setBaseStageVersion(2);
        request.setInstruction("将辅助线构造提前，并重新生成所有解题步骤。");
        when(taskMapper.findById(27L)).thenReturn(task);
        when(versionMapper.findCurrent(27L)).thenReturn(source);
        when(artifactMapper.findByTaskStageVersion(27L, "reasoning_graph", 2))
                .thenReturn(artifact(27L, "reasoning_graph", 2, "{\"nodes\":{\"old\":{}}}"));
        when(versionMapper.findMaxVersion(27L)).thenReturn(2);
        when(taskMapper.queueAutoEdit(27L, USER_ID, 3, "reasoning_graph", "problem_normalization"))
                .thenReturn(1);

        StageOperationResultVO result = service.autoEditStage(27L, "reasoning_graph", request);

        assertEquals("queued", result.getStatus());
        assertEquals(3, result.getCurrentVersion());
        ArgumentCaptor<MathVisionVersion> versionCaptor = ArgumentCaptor.forClass(MathVisionVersion.class);
        verify(versionMapper).clearCurrent(27L);
        verify(versionMapper).insert(versionCaptor.capture());
        MathVisionVersion revision = versionCaptor.getValue();
        assertEquals(1, revision.getPnVersion());
        assertEquals(2, revision.getRgVersion());
        assertNull(revision.getVsVersion());
        assertNull(revision.getCgVersion());
        assertNull(revision.getRrVersion());
        assertEquals("reasoning_graph", revision.getBranchStage());
        assertEquals("user_revision", revision.getChangeSource());
        assertEquals(request.getInstruction(), revision.getChangeInstruction());
        verify(taskMapper).queueAutoEdit(
                27L, USER_ID, 3, "reasoning_graph", "problem_normalization");
        verify(taskNotifier).notifyTaskChanged(27L, "auto_edit_queued");
    }

    @Test
    void retryFailedTaskReusesCurrentVersionAndStageArtifact() {
        MathVisionTask failed = task(29L, "failed", "render_result", 2);
        failed.setFailedStage("render_result");
        failed.setErrorType("render_error");
        failed.setErrorMessage("Manim traceback");
        MathVisionTask queued = task(29L, "queued", "render_result", 2);
        queued.setLastConfirmedStage("code_generation");
        MathVisionVersion source = version(29L, 2, 1, 2, 3, 4, 5);
        when(taskMapper.findById(29L)).thenReturn(failed, queued);
        when(versionMapper.findCurrent(29L)).thenReturn(source, source);
        when(taskMapper.queueRetryVersion(29L, USER_ID, 2, "render_result", "code_generation"))
                .thenReturn(1);

        assertEquals(2, service.startTask(29L).getCurrentVersion());

        verify(versionMapper).updateStagePointers(source);
        assertEquals(5, source.getRrVersion());
        verify(versionMapper, never()).clearCurrent(29L);
        verify(versionMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(taskMapper).queueRetryVersion(29L, USER_ID, 2, "render_result", "code_generation");
        verify(taskNotifier).notifyTaskChanged(29L, "retry_queued");
    }

    @Test
    void retryFailedTaskCanTargetSelectedCompletedStageInCurrentVersion() {
        MathVisionTask failed = task(30L, "failed", "render_result", 2);
        failed.setFailedStage("render_result");
        failed.setErrorType("render_error");
        failed.setErrorMessage("Render failed after code generation");
        MathVisionTask queued = task(30L, "queued", "code_generation", 2);
        queued.setLastConfirmedStage("visual_storyboard");
        MathVisionVersion source = version(30L, 2, 1, 2, 3, 4, 5);
        when(taskMapper.findById(30L)).thenReturn(failed, queued);
        when(versionMapper.findCurrent(30L)).thenReturn(source, source);
        when(taskMapper.queueRetryVersion(30L, USER_ID, 2, "code_generation", "visual_storyboard"))
                .thenReturn(1);

        assertEquals("code_generation", service.retryTaskStage(30L, "code_generation").getCurrentStage());

        verify(versionMapper).updateStagePointers(source);
        assertEquals(4, source.getCgVersion());
        assertNull(source.getRrVersion());
        verify(versionMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(taskMapper).queueRetryVersion(30L, USER_ID, 2, "code_generation", "visual_storyboard");
        verify(taskNotifier).notifyTaskChanged(30L, "retry_queued");
    }

    @Test
    void regeneratesCompletedStageOnNewVersionAndInvalidatesCurrentAndDownstreamPointers() {
        MathVisionTask completed = task(31L, "completed", "completed", 2);
        MathVisionTask queued = task(31L, "queued", "reasoning_graph", 3);
        queued.setLastConfirmedStage("problem_normalization");
        MathVisionVersion source = version(31L, 2, 1, 2, 3, 4, 5);
        when(taskMapper.findById(31L)).thenReturn(completed, queued);
        when(versionMapper.findCurrent(31L)).thenReturn(source, source);
        when(versionMapper.findMaxVersion(31L)).thenReturn(2);
        when(taskMapper.queueRegenerateVersion(
                31L, USER_ID, 3, "reasoning_graph", "problem_normalization"))
                .thenReturn(1);

        assertEquals(3, service.regenerateTaskStage(31L, "reasoning_graph").getCurrentVersion());

        ArgumentCaptor<MathVisionVersion> versionCaptor = ArgumentCaptor.forClass(MathVisionVersion.class);
        verify(versionMapper).clearCurrent(31L);
        verify(versionMapper).insert(versionCaptor.capture());
        MathVisionVersion regenerated = versionCaptor.getValue();
        assertEquals(2, regenerated.getBaseVersion());
        assertEquals("regenerate", regenerated.getChangeSource());
        assertEquals("reasoning_graph", regenerated.getBranchStage());
        assertEquals(1, regenerated.getPnVersion());
        assertNull(regenerated.getRgVersion());
        assertNull(regenerated.getVsVersion());
        assertNull(regenerated.getCgVersion());
        assertNull(regenerated.getRrVersion());
        assertEquals(2, source.getRgVersion());
        assertEquals(5, source.getRrVersion());
        verify(taskMapper).queueRegenerateVersion(
                31L, USER_ID, 3, "reasoning_graph", "problem_normalization");
        verify(taskNotifier).notifyTaskChanged(31L, "regenerate_queued");
    }

    @Test
    void rejectsAutoEditWhenBaselineStageVersionIsStale() {
        MathVisionTask task = task(28L, "waiting_confirm", "visual_storyboard", 2);
        MathVisionVersion source = version(28L, 2, 1, 1, 4, null, null);
        StageAutoEditRequestDTO request = new StageAutoEditRequestDTO();
        request.setBaseStageVersion(3);
        request.setInstruction("调整场景节奏");
        when(taskMapper.findById(28L)).thenReturn(task);
        when(versionMapper.findCurrent(28L)).thenReturn(source);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.autoEditStage(28L, "visual_storyboard", request));

        assertEquals("阶段版本已变化，请刷新后重新提交自动编辑", error.getMessage());
        verify(versionMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void switchingWaitingTaskToAutoQueuesNextStage() {
        MathVisionTask manualWaiting = task(32L, "waiting_confirm", "reasoning_graph", 1);
        manualWaiting.setMode("manual");
        MathVisionTask autoWaiting = task(32L, "waiting_confirm", "reasoning_graph", 1);
        autoWaiting.setMode("auto");
        MathVisionTask queued = task(32L, "queued", "visual_storyboard", 1);
        queued.setMode("auto");
        when(taskMapper.findById(32L)).thenReturn(manualWaiting, autoWaiting, queued);
        when(taskMapper.updateRuntimeSettings(
                32L, USER_ID, "auto", null, null, null)).thenReturn(1);
        when(taskMapper.queueTaskForRun(
                32L, USER_ID, "visual_storyboard", "reasoning_graph")).thenReturn(1);
        MathVisionTaskRuntimeSettingsRequestDTO request = new MathVisionTaskRuntimeSettingsRequestDTO();
        request.setMode("auto");

        assertEquals("queued", service.updateRuntimeSettings(32L, request).getStatus());

        verify(taskMapper).queueTaskForRun(
                32L, USER_ID, "visual_storyboard", "reasoning_graph");
        verify(taskNotifier).notifyTaskChanged(32L, "queued");
    }

    @Test
    void switchingModelUpdatesCredentialAndAppliesToFutureStages() {
        MathVisionTask current = task(33L, "completed", "completed", 1);
        current.setMode("manual");
        current.setProviderCode("openai");
        current.setModelName("old-model");
        current.setSelectedModelConfigId(3L);
        MathVisionTask updated = task(33L, "completed", "completed", 1);
        updated.setMode("manual");
        updated.setProviderCode("openai");
        updated.setModelName("new-model");
        updated.setSelectedModelConfigId(8L);
        when(taskMapper.findById(33L)).thenReturn(current, updated);

        ProviderCatalog provider = new ProviderCatalog();
        provider.setCode("openai");
        provider.setEnabled(true);
        ModelCatalog model = new ModelCatalog();
        model.setModelName("new-model");
        model.setContextWindow(128_000);
        model.setMaxOutputTokens(16_000);
        model.setTemperature(0.6D);
        when(catalog.findEnabled("openai")).thenReturn(provider);
        when(catalog.findModel("openai", "new-model")).thenReturn(model);
        when(configMapper.findByOwnerAndProvider(USER_ID, "openai")).thenReturn(
                LlmModelConfig.builder()
                        .id(8L)
                        .ownerUserId(USER_ID)
                        .provider("openai")
                        .apiKeyEncrypted("encrypted-key")
                        .status("enabled")
                        .build());
        when(taskMapper.updateRuntimeSettings(
                33L, USER_ID, "manual", 8L, "openai", "new-model")).thenReturn(1);
        MathVisionTaskRuntimeSettingsRequestDTO request = new MathVisionTaskRuntimeSettingsRequestDTO();
        request.setProviderCode("openai");
        request.setModelName("new-model");

        assertEquals("new-model", service.updateRuntimeSettings(33L, request).getModelName());

        verify(taskMapper).updateRuntimeSettings(
                33L, USER_ID, "manual", 8L, "openai", "new-model");
        verify(taskNotifier).notifyTaskChanged(33L, "runtime_settings_updated");
    }

    @Test
    void updatesOwnedTaskTitleAndReturnsLatestDetail() {
        MathVisionTask task = task(34L, "waiting_confirm", "reasoning_graph", 1);
        when(taskMapper.findById(34L)).thenReturn(task, task);
        when(chatSessionMapper.updateTitle("session-34", "新的任务标题")).thenReturn(1);
        when(chatSessionMapper.findBySessionId("session-34")).thenReturn(
                ChatSession.builder()
                        .sessionId("session-34")
                        .userId(USER_ID)
                        .title("新的任务标题")
                        .purpose("mathvision")
                        .build());
        MathVisionTaskTitleUpdateRequestDTO request = new MathVisionTaskTitleUpdateRequestDTO();
        request.setTitle("  新的任务标题  ");

        assertEquals("新的任务标题", service.updateTaskTitle(34L, request).getTitle());

        verify(chatSessionMapper).updateTitle("session-34", "新的任务标题");
        verify(taskNotifier).notifyTaskChanged(34L, "title_updated");
    }

    private MathVisionTask task(Long id, String status, String currentStage, Integer currentVersion) {
        return MathVisionTask.builder()
                .id(id)
                .userId(USER_ID)
                .sessionId("session-" + id)
                .status(status)
                .currentStage(currentStage)
                .currentVersion(currentVersion)
                .inputAssetsJson("[]")
                .cancelRequested(false)
                .build();
    }

    private MathVisionVersion version(Long taskId,
                                      Integer version,
                                      Integer pn,
                                      Integer rg,
                                      Integer vs,
                                      Integer cg,
                                      Integer rr) {
        return MathVisionVersion.builder()
                .taskId(taskId)
                .version(version)
                .baseVersion(version > 1 ? version - 1 : null)
                .pnVersion(pn)
                .rgVersion(rg)
                .vsVersion(vs)
                .cgVersion(cg)
                .rrVersion(rr)
                .changeSource(version == 1 ? "initial_generation" : "manual_edit")
                .changeSummary("V" + version + " snapshot")
                .build();
    }

    private MathVisionArtifact artifact(Long taskId, String stage, Integer version, String json) {
        return MathVisionArtifact.builder()
                .taskId(taskId)
                .stage(stage)
                .version(version)
                .artifactJson(json)
                .build();
    }
}
