package com.kwang.study.mathvision.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.auth.custom.CustomUserDetails;
import com.kwang.study.fs.dto.result.FileObjectResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.llm.mapper.ChatSessionMapper;
import com.kwang.study.llm.pojo.ChatSession;
import com.kwang.study.mathvision.dto.MathVisionSquareItemVO;
import com.kwang.study.mathvision.dto.MathVisionSquareLoadResultVO;
import com.kwang.study.mathvision.dto.PageResultVO;
import com.kwang.study.mathvision.mapper.LlmModelConfigMapper;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionSquarePostMapper;
import com.kwang.study.mathvision.mapper.MathVisionStageResultMapper;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.MathVisionArtifact;
import com.kwang.study.mathvision.pojo.MathVisionSquarePost;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.pojo.MathVisionVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MathVisionSquareServiceTest {

    private static final Long USER_ID = 7L;

    @Mock private MathVisionSquarePostMapper squarePostMapper;
    @Mock private MathVisionTaskMapper taskMapper;
    @Mock private MathVisionVersionMapper versionMapper;
    @Mock private MathVisionArtifactMapper artifactMapper;
    @Mock private MathVisionStageResultMapper stageResultMapper;
    @Mock private ChatSessionMapper chatSessionMapper;
    @Mock private LlmModelConfigMapper configMapper;
    @Mock private FileStorageService fileStorageService;

    private MathVisionSquareService service;

    @BeforeEach
    void setUp() {
        CustomUserDetails principal = CustomUserDetails.builder()
                .id(USER_ID)
                .username("square-user")
                .authorities(Collections.emptyList())
                .enabled(true)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        service = new MathVisionSquareService(
                squarePostMapper,
                taskMapper,
                versionMapper,
                artifactMapper,
                stageResultMapper,
                chatSessionMapper,
                configMapper,
                fileStorageService,
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void publishesCompletedCurrentVersion() {
        MathVisionTask task = completedTask(11L, USER_ID, 3);
        when(taskMapper.findById(11L)).thenReturn(task);
        when(versionMapper.findByTaskVersion(11L, 3)).thenReturn(
                MathVisionVersion.builder().taskId(11L).version(3).rrVersion(2).build());
        when(chatSessionMapper.findBySessionId("source-session")).thenReturn(
                ChatSession.builder().sessionId("source-session").userId(USER_ID).title("圆弧最值").build());
        doAnswer(invocation -> {
            MathVisionSquarePost post = invocation.getArgument(0);
            post.setId(91L);
            return null;
        }).when(squarePostMapper).insert(any(MathVisionSquarePost.class));

        MathVisionSquareItemVO published = service.publishCurrentVersion(11L);

        assertEquals(91L, published.getShareId());
        assertEquals("圆弧最值", published.getTitle());
        assertEquals(3, published.getVersion());
        assertTrue(published.getMine());
        ArgumentCaptor<MathVisionSquarePost> postCaptor = ArgumentCaptor.forClass(MathVisionSquarePost.class);
        verify(squarePostMapper).insert(postCaptor.capture());
        assertEquals("/mathvision/task-11/v3/final/final.mp4", postCaptor.getValue().getArtifactPath());
    }

    @Test
    void listsOnlyCurrentUsersSharesWhenRequested() {
        MathVisionSquarePost post = MathVisionSquarePost.builder()
                .id(92L)
                .taskId(11L)
                .version(3)
                .ownerUserId(USER_ID)
                .title("我的圆弧动画")
                .outputTarget("manim")
                .build();
        when(squarePostMapper.page("圆弧", "manim", USER_ID, 0, 24))
                .thenReturn(List.of(post));
        when(squarePostMapper.count("圆弧", "manim", USER_ID)).thenReturn(1L);

        PageResultVO<MathVisionSquareItemVO> result =
                service.listPublished("圆弧", "manim", true, 1, 24);

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertTrue(result.getRecords().get(0).getMine());
        verify(squarePostMapper).page("圆弧", "manim", USER_ID, 0, 24);
        verify(squarePostMapper).count("圆弧", "manim", USER_ID);
    }

    @Test
    void loadsPublishedVersionAsIndependentWorkbenchTask() throws Exception {
        String sourcePath = "/mathvision/task-5/v2/final/final.mp4";
        MathVisionSquarePost post = MathVisionSquarePost.builder()
                .id(51L)
                .taskId(5L)
                .version(2)
                .ownerUserId(99L)
                .title("共享动画")
                .outputTarget("manim")
                .artifactPath(sourcePath)
                .artifactType("mp4")
                .build();
        when(squarePostMapper.findById(51L)).thenReturn(post);
        MathVisionTask sourceTask = completedTask(5L, 99L, 2);
        sourceTask.setInputAssetsJson("[]");
        when(taskMapper.findById(5L)).thenReturn(sourceTask);
        when(versionMapper.findByTaskVersion(5L, 2)).thenReturn(
                MathVisionVersion.builder()
                        .taskId(5L)
                        .version(2)
                        .rrVersion(3)
                        .workflowSummaryJson("{\"artifactPath\":\"" + sourcePath + "\"}")
                        .build());
        when(artifactMapper.findByTaskStageVersion(5L, "render_result", 3)).thenReturn(
                MathVisionArtifact.builder()
                        .id(33L)
                        .taskId(5L)
                        .stage("render_result")
                        .version(3)
                        .artifactJson("{\"artifactPath\":\"" + sourcePath + "\",\"artifactType\":\"mp4\"}")
                        .build());
        FileObjectResult file = new FileObjectResult();
        file.setMimeTypeName("video/mp4");
        file.setContent(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(fileStorageService.getFileObject(sourcePath)).thenReturn(file);
        doAnswer(invocation -> {
            MathVisionTask imported = invocation.getArgument(0);
            imported.setId(101L);
            return null;
        }).when(taskMapper).insert(any(MathVisionTask.class));
        doAnswer(invocation -> {
            MathVisionArtifact imported = invocation.getArgument(0);
            imported.setId(201L);
            return null;
        }).when(artifactMapper).insert(any(MathVisionArtifact.class));

        MathVisionSquareLoadResultVO loaded = service.loadIntoWorkbench(51L);

        assertEquals(101L, loaded.getTaskId());
        assertEquals("共享动画（来自创作广场）", loaded.getTitle());
        ArgumentCaptor<MathVisionTask> taskCaptor = ArgumentCaptor.forClass(MathVisionTask.class);
        verify(taskMapper).updateFinalArtifact(taskCaptor.capture());
        assertEquals("/mathvision/task-101/v1/final/final.mp4", taskCaptor.getValue().getFinalArtifactPath());

        ArgumentCaptor<MathVisionArtifact> artifactCaptor = ArgumentCaptor.forClass(MathVisionArtifact.class);
        verify(artifactMapper).insert(artifactCaptor.capture());
        assertTrue(artifactCaptor.getValue().getArtifactJson()
                .contains("/mathvision/task-101/v1/final/final.mp4"));

        ArgumentCaptor<MathVisionVersion> versionCaptor = ArgumentCaptor.forClass(MathVisionVersion.class);
        verify(versionMapper).insert(versionCaptor.capture());
        assertEquals(1, versionCaptor.getValue().getRrVersion());
        assertEquals("square_import", versionCaptor.getValue().getChangeSource());
        verify(squarePostMapper).incrementLoadCount(51L);
        verify(fileStorageService).createFile(
                eq("/mathvision/task-101/v1/final/final.mp4"), any(ByteArrayInputStream.class), eq("video/mp4"));
    }

    @Test
    void rejectsLoadingOwnSharedTask() {
        MathVisionSquarePost post = MathVisionSquarePost.builder()
                .id(61L)
                .taskId(12L)
                .version(1)
                .ownerUserId(USER_ID)
                .build();
        when(squarePostMapper.findById(61L)).thenReturn(post);
        when(taskMapper.findById(12L)).thenReturn(completedTask(12L, USER_ID, 1));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.loadIntoWorkbench(61L));

        assertEquals("这是你自己分享的成果，原任务已经在工作台中，无需重复加载", error.getMessage());
        verifyNoInteractions(versionMapper, chatSessionMapper, fileStorageService);
    }

    private MathVisionTask completedTask(Long taskId, Long userId, Integer version) {
        return MathVisionTask.builder()
                .id(taskId)
                .sessionId("source-session")
                .userId(userId)
                .inputText("求圆弧上的最小值")
                .inputSourceType("text")
                .inputAssetsJson("[]")
                .mode("auto")
                .outputTarget("manim")
                .status("completed")
                .currentStage("completed")
                .providerCode("openai")
                .modelName("model")
                .currentVersion(version)
                .finalArtifactPath("/mathvision/task-" + taskId + "/v" + version + "/final/final.mp4")
                .finalArtifactType("mp4")
                .build();
    }
}
