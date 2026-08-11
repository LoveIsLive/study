package com.kwang.study.mathvision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionStageResultMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.MathVisionArtifact;
import com.kwang.study.mathvision.pojo.MathVisionStageResult;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.pojo.MathVisionVersion;
import com.kwang.study.mathvision.workflow.model.CodeResult;
import com.kwang.study.mathvision.workflow.model.CodeFixSource;
import com.kwang.study.mathvision.workflow.model.RenderResult;
import com.kwang.study.mathvision.workflow.util.CodeFixAcceptanceValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MathVisionFinalCodeArtifactService {

    private static final Logger log = LoggerFactory.getLogger(MathVisionFinalCodeArtifactService.class);

    private final MathVisionArtifactMapper artifactMapper;
    private final MathVisionStageResultMapper stageResultMapper;
    private final MathVisionVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    public MathVisionFinalCodeArtifactService(MathVisionArtifactMapper artifactMapper,
                                              MathVisionStageResultMapper stageResultMapper,
                                              MathVisionVersionMapper versionMapper,
                                              ObjectMapper objectMapper) {
        this.artifactMapper = artifactMapper;
        this.stageResultMapper = stageResultMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WritebackResult persistFinalCode(MathVisionTask task, RenderResult renderResult) {
        if (task == null || task.getId() == null || renderResult == null) {
            throw new IllegalArgumentException("Task and successful render result are required");
        }
        String finalCode = renderResult.getFinalGeneratedCode();
        if (!StringUtils.hasText(finalCode)) {
            throw new IllegalArgumentException("Successful render result does not contain final generated code");
        }

        MathVisionVersion taskVersion = resolveTaskVersion(task);
        Integer currentCodeVersion = taskVersion.getCgVersion();
        if (currentCodeVersion == null) {
            throw new IllegalStateException("Current task version does not reference a code-generation artifact");
        }
        MathVisionArtifact currentArtifact = artifactMapper.findByTaskStageVersion(
                task.getId(), StageEnum.CODE_GENERATION.getCode(), currentCodeVersion);
        if (currentArtifact == null || !StringUtils.hasText(currentArtifact.getArtifactJson())) {
            throw new IllegalStateException("Current code-generation artifact is missing");
        }

        CodeResult finalCodeResult = readCodeResult(currentArtifact.getArtifactJson());
        if (finalCode.equals(finalCodeResult.getGeneratedCode())) {
            return WritebackResult.unchanged(currentCodeVersion);
        }
        CodeFixAcceptanceValidator.Decision acceptance = CodeFixAcceptanceValidator.evaluate(
                finalCodeResult.getGeneratedCode(),
                finalCode,
                StringUtils.hasText(renderResult.getOutputTarget())
                        ? renderResult.getOutputTarget()
                        : finalCodeResult.getOutputTarget(),
                CodeFixSource.CODE_RENDER);
        if (!acceptance.isAccepted()) {
            throw new IllegalStateException("Final rendered code was not written back because it violates the "
                    + "accepted code artifact contract: " + acceptance.summarizeIssues());
        }
        finalCodeResult.setGeneratedCode(finalCode);
        if (StringUtils.hasText(renderResult.getSceneName())) {
            finalCodeResult.setSceneName(renderResult.getSceneName());
        }
        if (StringUtils.hasText(renderResult.getOutputTarget())) {
            finalCodeResult.setOutputTarget(renderResult.getOutputTarget());
        }

        currentArtifact.setArtifactJson(writeJson(finalCodeResult));
        currentArtifact.setChangeSource("auto_fix");
        currentArtifact.setChangeSummary("adopt final code used by successful render");
        artifactMapper.updateArtifactJson(currentArtifact);
        updateStageResult(currentArtifact, finalCodeResult);

        log.debug("MathVision final render code written back in place, taskId={}, taskVersion={}, "
                        + "codeVersion={}, codeLines={}",
                task.getId(), taskVersion.getVersion(), currentCodeVersion, finalCodeResult.codeLineCount());
        return WritebackResult.updated(currentCodeVersion, currentCodeVersion);
    }

    private MathVisionVersion resolveTaskVersion(MathVisionTask task) {
        MathVisionVersion version = task.getCurrentVersion() != null
                ? versionMapper.findByTaskVersion(task.getId(), task.getCurrentVersion())
                : null;
        if (version == null) {
            version = versionMapper.findCurrent(task.getId());
        }
        if (version == null || version.getVersion() == null) {
            throw new IllegalStateException("Current MathVision task version is missing");
        }
        return version;
    }

    private CodeResult readCodeResult(String artifactJson) {
        try {
            return objectMapper.readValue(artifactJson, CodeResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse current code-generation artifact: " + e.getMessage(), e);
        }
    }

    private void updateStageResult(MathVisionArtifact currentArtifact,
                                   CodeResult finalCodeResult) {
        if (currentArtifact.getId() == null) {
            return;
        }
        MathVisionStageResult currentResult = stageResultMapper.findByArtifactId(currentArtifact.getId());
        String resultJson = updateResultJson(
                currentResult != null ? currentResult.getResultJson() : "{}",
                finalCodeResult,
                currentArtifact.getVersion());
        if (currentResult != null) {
            currentResult.setResultJson(resultJson);
            stageResultMapper.updateResultJson(currentResult);
        } else {
            MathVisionStageResult insertedResult = MathVisionStageResult.builder()
                    .taskId(currentArtifact.getTaskId())
                    .artifactId(currentArtifact.getId())
                    .sessionId(currentArtifact.getSessionId())
                    .userId(currentArtifact.getUserId())
                    .stage(currentArtifact.getStage())
                    .version(currentArtifact.getVersion())
                    .resultJson(resultJson)
                    .build();
            stageResultMapper.insert(insertedResult);
        }
    }

    private String updateResultJson(String resultJson, CodeResult finalCodeResult, int finalCodeVersion) {
        try {
            JsonNode parsed = objectMapper.readTree(resultJson);
            ObjectNode result = parsed != null && parsed.isObject()
                    ? (ObjectNode) parsed.deepCopy()
                    : objectMapper.createObjectNode();
            result.put("lineCount", finalCodeResult.codeLineCount());
            result.put("sceneName", finalCodeResult.getSceneName());
            result.put("artifactName", finalCodeResult.getArtifactName());
            result.put("artifactFormat", finalCodeResult.getArtifactFormat());
            result.put("outputTarget", finalCodeResult.getOutputTarget());
            result.put("finalCodeUpdatedFromRender", true);
            result.put("finalCodeStageVersion", finalCodeVersion);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to copy code-generation result metadata: " + e.getMessage(), e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize final code-generation artifact: " + e.getMessage(), e);
        }
    }

    public static final class WritebackResult {
        private final boolean updated;
        private final int previousVersion;
        private final int finalVersion;

        private WritebackResult(boolean updated, int previousVersion, int finalVersion) {
            this.updated = updated;
            this.previousVersion = previousVersion;
            this.finalVersion = finalVersion;
        }

        private static WritebackResult unchanged(int version) {
            return new WritebackResult(false, version, version);
        }

        private static WritebackResult updated(int previousVersion, int finalVersion) {
            return new WritebackResult(true, previousVersion, finalVersion);
        }

        public boolean isUpdated() {
            return updated;
        }

        public int getPreviousVersion() {
            return previousVersion;
        }

        public int getFinalVersion() {
            return finalVersion;
        }
    }
}
