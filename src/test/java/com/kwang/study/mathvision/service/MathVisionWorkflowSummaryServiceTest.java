package com.kwang.study.mathvision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionStageResultMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.MathVisionArtifact;
import com.kwang.study.mathvision.pojo.MathVisionStageResult;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.pojo.MathVisionVersion;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MathVisionWorkflowSummaryServiceTest {

    @Test
    void buildsCrossStageDiagnosticSummaryAndPersistsIt() throws Exception {
        MathVisionVersionMapper versionMapper = mock(MathVisionVersionMapper.class);
        MathVisionArtifactMapper artifactMapper = mock(MathVisionArtifactMapper.class);
        MathVisionStageResultMapper resultMapper = mock(MathVisionStageResultMapper.class);
        ObjectMapper mapper = new ObjectMapper();

        MathVisionTask task = MathVisionTask.builder()
                .id(91L)
                .currentVersion(3)
                .status("completed")
                .currentStage("completed")
                .providerCode("zhipu")
                .modelName("GLM-5V-Turbo")
                .outputTarget("manim")
                .build();
        MathVisionVersion version = MathVisionVersion.builder()
                .taskId(91L)
                .version(3)
                .cgVersion(2)
                .rrVersion(1)
                .build();
        when(versionMapper.findByTaskVersion(91L, 3)).thenReturn(version);

        MathVisionArtifact codeArtifact = MathVisionArtifact.builder().id(12L).changeSource("initial_generation").build();
        MathVisionArtifact renderArtifact = MathVisionArtifact.builder().id(13L).changeSource("initial_generation").build();
        when(artifactMapper.findByTaskStageVersion(91L, StageEnum.CODE_GENERATION.getCode(), 2))
                .thenReturn(codeArtifact);
        when(artifactMapper.findByTaskStageVersion(91L, StageEnum.RENDER_RESULT.getCode(), 1))
                .thenReturn(renderArtifact);

        when(resultMapper.findByTaskStageVersion(91L, StageEnum.CODE_GENERATION.getCode(), 2))
                .thenReturn(MathVisionStageResult.builder()
                        .resultJson("{\"api_calls\":4,\"line_count\":180,\"code_generation_attempts\":2," +
                                "\"code_evaluation_gate_reason\":\"approved\"}")
                        .build());
        when(resultMapper.findByTaskStageVersion(91L, StageEnum.RENDER_RESULT.getCode(), 1))
                .thenReturn(MathVisionStageResult.builder()
                        .resultJson("{\"api_calls\":3,\"render_success\":true,\"render_final_success\":true," +
                                "\"scene_evaluation_approved\":true," +
                                "\"scene_evaluation\":{\"gate_reason\":\"passed\"}," +
                                "\"render_result\":{\"attempts\":2,\"artifact_path\":\"/final.mp4\"}}")
                        .build());

        MathVisionWorkflowSummaryService service = new MathVisionWorkflowSummaryService(
                versionMapper, artifactMapper, resultMapper, mapper);
        String summary = service.refresh(task);

        JsonNode root = mapper.readTree(summary);
        assertEquals(7, root.path("total_api_calls").asInt());
        assertEquals(180, root.path("code_lines").asInt());
        assertEquals(2, root.path("code_generation_attempts").asInt());
        assertTrue(root.path("render_success").asBoolean());
        assertEquals("passed", root.path("scene_evaluation_gate_reason").asText());

        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(versionMapper).updateWorkflowSummary(org.mockito.ArgumentMatchers.eq(91L),
                org.mockito.ArgumentMatchers.eq(3), summaryCaptor.capture());
        assertTrue(summaryCaptor.getValue().contains("\"code_lines\" : 180"));
    }
}
