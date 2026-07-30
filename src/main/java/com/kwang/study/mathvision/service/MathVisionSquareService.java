package com.kwang.study.mathvision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.fs.dto.result.FileObjectResult;
import com.kwang.study.fs.exception.PathAlreadyExistsException;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.llm.mapper.ChatSessionMapper;
import com.kwang.study.llm.pojo.ChatSession;
import com.kwang.study.mathvision.dto.MathVisionSquareItemVO;
import com.kwang.study.mathvision.dto.MathVisionSquareLoadResultVO;
import com.kwang.study.mathvision.dto.PageResultVO;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.mapper.LlmModelConfigMapper;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionSquarePostMapper;
import com.kwang.study.mathvision.mapper.MathVisionStageResultMapper;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.LlmModelConfig;
import com.kwang.study.mathvision.pojo.MathVisionArtifact;
import com.kwang.study.mathvision.pojo.MathVisionSquarePost;
import com.kwang.study.mathvision.pojo.MathVisionStageResult;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.pojo.MathVisionVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MathVisionSquareService {

    private static final Logger log = LoggerFactory.getLogger(MathVisionSquareService.class);
    private static final String PURPOSE = "mathvision";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MathVisionSquarePostMapper squarePostMapper;
    private final MathVisionTaskMapper taskMapper;
    private final MathVisionVersionMapper versionMapper;
    private final MathVisionArtifactMapper artifactMapper;
    private final MathVisionStageResultMapper stageResultMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final LlmModelConfigMapper configMapper;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    public MathVisionSquareService(MathVisionSquarePostMapper squarePostMapper,
                                   MathVisionTaskMapper taskMapper,
                                   MathVisionVersionMapper versionMapper,
                                   MathVisionArtifactMapper artifactMapper,
                                   MathVisionStageResultMapper stageResultMapper,
                                   ChatSessionMapper chatSessionMapper,
                                   LlmModelConfigMapper configMapper,
                                   FileStorageService fileStorageService,
                                   ObjectMapper objectMapper) {
        this.squarePostMapper = squarePostMapper;
        this.taskMapper = taskMapper;
        this.versionMapper = versionMapper;
        this.artifactMapper = artifactMapper;
        this.stageResultMapper = stageResultMapper;
        this.chatSessionMapper = chatSessionMapper;
        this.configMapper = configMapper;
        this.fileStorageService = fileStorageService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MathVisionSquareItemVO publishCurrentVersion(Long taskId) {
        Long userId = requireCurrentUserId();
        MathVisionTask task = requireOwnedTask(taskId, userId);
        if (!"completed".equals(task.getStatus())) {
            throw new IllegalArgumentException("只有已完成并生成最终成果的任务可以分享到创作广场");
        }
        MathVisionVersion version = versionMapper.findByTaskVersion(task.getId(), task.getCurrentVersion());
        if (version == null || version.getRrVersion() == null
                || !StringUtils.hasText(task.getFinalArtifactPath())
                || !StringUtils.hasText(task.getFinalArtifactType())) {
            throw new IllegalArgumentException("当前任务版本还没有可分享的最终成果");
        }

        MathVisionSquarePost existing = squarePostMapper.findByTaskVersion(task.getId(), version.getVersion());
        if (existing != null) {
            return toItemVO(existing, userId);
        }

        ChatSession session = chatSessionMapper.findBySessionId(task.getSessionId());
        String title = session != null && StringUtils.hasText(session.getTitle())
                ? session.getTitle()
                : "MathVision 教学成果";
        MathVisionSquarePost post = MathVisionSquarePost.builder()
                .taskId(task.getId())
                .version(version.getVersion())
                .ownerUserId(userId)
                .title(limit(title, 255))
                .summary(limit(task.getInputText(), 500))
                .outputTarget(task.getOutputTarget())
                .artifactPath(task.getFinalArtifactPath())
                .artifactType(task.getFinalArtifactType())
                .authorName(AuthenticationUserUtil.getCurrentUserName())
                .loadCount(0)
                .build();
        squarePostMapper.insert(post);
        return toItemVO(post, userId);
    }

    public PageResultVO<MathVisionSquareItemVO> listPublished(String keyword,
                                                              String outputTarget,
                                                              boolean mineOnly,
                                                              int page,
                                                              int size) {
        Long userId = requireCurrentUserId();
        Long ownerUserId = mineOnly ? userId : null;
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        int offset = (p - 1) * s;
        List<MathVisionSquareItemVO> records = new ArrayList<>();
        for (MathVisionSquarePost post : squarePostMapper.page(
                normalizeFilter(keyword), normalizeFilter(outputTarget), ownerUserId, offset, s)) {
            records.add(toItemVO(post, userId));
        }
        long total = squarePostMapper.count(
                normalizeFilter(keyword), normalizeFilter(outputTarget), ownerUserId);
        return PageResultVO.<MathVisionSquareItemVO>builder()
                .records(records)
                .total(total)
                .build();
    }

    @Transactional
    public void unpublish(Long shareId) {
        Long userId = requireCurrentUserId();
        if (squarePostMapper.deleteOwned(shareId, userId) == 0) {
            throw new IllegalArgumentException("创作广场成果不存在或无权取消分享");
        }
    }

    @Transactional
    public MathVisionSquareLoadResultVO loadIntoWorkbench(Long shareId) {
        Long userId = requireCurrentUserId();
        MathVisionSquarePost post = squarePostMapper.findById(shareId);
        if (post == null) {
            throw new IllegalArgumentException("创作广场成果不存在或已取消分享");
        }
        MathVisionTask sourceTask = taskMapper.findById(post.getTaskId());
        if (sourceTask == null) {
            throw new IllegalArgumentException("来源任务已不可用");
        }
        if (userId.equals(sourceTask.getUserId())) {
            throw new IllegalArgumentException("这是你自己分享的成果，原任务已经在工作台中，无需重复加载");
        }
        MathVisionVersion sourceVersion = versionMapper.findByTaskVersion(
                sourceTask.getId(), post.getVersion());
        if (sourceVersion == null) {
            throw new IllegalArgumentException("来源任务版本已不可用");
        }

        List<String> copiedStoragePaths = new ArrayList<>();
        registerRollbackCleanup(copiedStoragePaths);
        try {
            Map<String, String> pathReplacements = new LinkedHashMap<>();
            String copiedInputAssetsJson = copyInputAssets(
                    sourceTask.getInputAssetsJson(), pathReplacements, copiedStoragePaths);

            String sessionId = UUID.randomUUID().toString();
            String importedTitle = limit(post.getTitle() + "（来自创作广场）", 255);
            chatSessionMapper.insert(ChatSession.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .title(importedTitle)
                    .purpose(PURPOSE)
                    .build());

            LlmModelConfig userConfig = StringUtils.hasText(sourceTask.getProviderCode())
                    ? configMapper.findByOwnerAndProvider(userId, sourceTask.getProviderCode())
                    : null;
            MathVisionTask importedTask = MathVisionTask.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .inputText(sourceTask.getInputText())
                    .inputSourceType(sourceTask.getInputSourceType())
                    .inputAssetsJson(copiedInputAssetsJson)
                    .mode(sourceTask.getMode())
                    .outputTarget(sourceTask.getOutputTarget())
                    .status("completed")
                    .currentStage(StageEnum.COMPLETED.getCode())
                    .selectedModelConfigId(userConfig != null ? userConfig.getId() : null)
                    .providerCode(sourceTask.getProviderCode())
                    .modelName(sourceTask.getModelName())
                    .currentVersion(1)
                    .build();
            taskMapper.insert(importedTask);

            String copiedFinalPath = copyFinalArtifact(
                    post, importedTask.getId(), copiedStoragePaths);
            pathReplacements.put(post.getArtifactPath(), copiedFinalPath);
            importedTask.setFinalArtifactPath(copiedFinalPath);
            importedTask.setFinalArtifactType(post.getArtifactType());
            taskMapper.updateFinalArtifact(importedTask);
            taskMapper.updateLastConfirmedStage(importedTask.getId(), StageEnum.RENDER_RESULT.getCode());

            Integer pnVersion = copyStage(sourceTask, sourceVersion.getPnVersion(),
                    StageEnum.PROBLEM_NORMALIZATION, importedTask, pathReplacements);
            Integer rgVersion = copyStage(sourceTask, sourceVersion.getRgVersion(),
                    StageEnum.REASONING_GRAPH, importedTask, pathReplacements);
            Integer vsVersion = copyStage(sourceTask, sourceVersion.getVsVersion(),
                    StageEnum.VISUAL_STORYBOARD, importedTask, pathReplacements);
            Integer cgVersion = copyStage(sourceTask, sourceVersion.getCgVersion(),
                    StageEnum.CODE_GENERATION, importedTask, pathReplacements);
            Integer rrVersion = copyStage(sourceTask, sourceVersion.getRrVersion(),
                    StageEnum.RENDER_RESULT, importedTask, pathReplacements);

            versionMapper.insert(MathVisionVersion.builder()
                    .taskId(importedTask.getId())
                    .version(1)
                    .pnVersion(pnVersion)
                    .rgVersion(rgVersion)
                    .vsVersion(vsVersion)
                    .cgVersion(cgVersion)
                    .rrVersion(rrVersion)
                    .branchStage(StageEnum.RENDER_RESULT.getCode())
                    .changeSource("square_import")
                    .changeSummary("从创作广场加载：" + post.getTitle())
                    .workflowSummaryJson(replacePaths(sourceVersion.getWorkflowSummaryJson(), pathReplacements))
                    .isCurrent(true)
                    .build());
            squarePostMapper.incrementLoadCount(post.getId());

            return MathVisionSquareLoadResultVO.builder()
                    .taskId(importedTask.getId())
                    .sessionId(sessionId)
                    .title(importedTitle)
                    .status("completed")
                    .currentStage(StageEnum.COMPLETED.getCode())
                    .build();
        } catch (RuntimeException e) {
            cleanupCopiedPaths(copiedStoragePaths);
            throw e;
        } catch (Exception e) {
            cleanupCopiedPaths(copiedStoragePaths);
            throw new IllegalStateException("加载创作广场成果失败：" + e.getMessage(), e);
        }
    }

    private Integer copyStage(MathVisionTask sourceTask,
                              Integer sourceStageVersion,
                              StageEnum stage,
                              MathVisionTask importedTask,
                              Map<String, String> pathReplacements) {
        if (sourceStageVersion == null) {
            return null;
        }
        MathVisionArtifact sourceArtifact = artifactMapper.findByTaskStageVersion(
                sourceTask.getId(), stage.getCode(), sourceStageVersion);
        if (sourceArtifact == null) {
            throw new IllegalStateException("来源阶段产物缺失：" + stage.getCode() + " V" + sourceStageVersion);
        }
        MathVisionArtifact importedArtifact = MathVisionArtifact.builder()
                .taskId(importedTask.getId())
                .sessionId(importedTask.getSessionId())
                .userId(importedTask.getUserId())
                .stage(stage.getCode())
                .version(1)
                .artifactJson(replacePaths(sourceArtifact.getArtifactJson(), pathReplacements))
                .changeSource("square_import")
                .changeSummary("从创作广场加载完整阶段产物")
                .build();
        artifactMapper.insert(importedArtifact);

        MathVisionStageResult sourceResult = stageResultMapper.findByTaskStageVersion(
                sourceTask.getId(), stage.getCode(), sourceStageVersion);
        if (sourceResult != null) {
            stageResultMapper.insert(MathVisionStageResult.builder()
                    .taskId(importedTask.getId())
                    .artifactId(importedArtifact.getId())
                    .sessionId(importedTask.getSessionId())
                    .userId(importedTask.getUserId())
                    .stage(stage.getCode())
                    .version(1)
                    .resultJson(replacePaths(sourceResult.getResultJson(), pathReplacements))
                    .build());
        }
        return 1;
    }

    private String copyInputAssets(String inputAssetsJson,
                                   Map<String, String> pathReplacements,
                                   List<String> copiedStoragePaths) throws Exception {
        if (!StringUtils.hasText(inputAssetsJson)) {
            return "[]";
        }
        JsonNode root = objectMapper.readTree(inputAssetsJson);
        if (!(root instanceof ArrayNode)) {
            return inputAssetsJson;
        }
        for (JsonNode item : root) {
            if (!(item instanceof ObjectNode)) {
                continue;
            }
            ObjectNode asset = (ObjectNode) item;
            String sourcePath = asset.path("filePath").asText("");
            if (!StringUtils.hasText(sourcePath)) {
                continue;
            }
            String copiedPath = pathReplacements.get(sourcePath);
            if (!StringUtils.hasText(copiedPath)) {
                copiedPath = "/mathvision/" + UUID.randomUUID() + extensionOf(sourcePath);
                copyStorageObject(sourcePath, copiedPath, copiedStoragePaths);
                pathReplacements.put(sourcePath, copiedPath);
            }
            asset.put("filePath", copiedPath);
            asset.put("source", "square_import");
        }
        return objectMapper.writeValueAsString(root);
    }

    private String copyFinalArtifact(MathVisionSquarePost post,
                                     Long importedTaskId,
                                     List<String> copiedStoragePaths) throws Exception {
        String extension = extensionOf(post.getArtifactPath());
        if (!StringUtils.hasText(extension)) {
            extension = "html".equalsIgnoreCase(post.getArtifactType()) ? ".html" : ".mp4";
        }
        String destination = "/mathvision/task-" + importedTaskId + "/v1/final/final" + extension;
        copyStorageObject(post.getArtifactPath(), destination, copiedStoragePaths);
        return destination;
    }

    private void copyStorageObject(String sourcePath,
                                   String destinationPath,
                                   List<String> copiedStoragePaths) throws Exception {
        ensureParentDirectories(destinationPath);
        FileObjectResult source = fileStorageService.getFileObject(sourcePath);
        if (source == null || source.getContent() == null) {
            throw new IllegalStateException("共享成果文件不可读取：" + sourcePath);
        }
        try (InputStream input = source.getContent()) {
            fileStorageService.createFile(destinationPath, input,
                    StringUtils.hasText(source.getMimeTypeName())
                            ? source.getMimeTypeName()
                            : "application/octet-stream");
        }
        copiedStoragePaths.add(destinationPath);
    }

    private void ensureParentDirectories(String path) throws Exception {
        int slash = path.lastIndexOf('/');
        if (slash <= 0) {
            return;
        }
        String[] parts = path.substring(1, slash).split("/");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            current.append('/').append(part);
            try {
                fileStorageService.createDirectory(current.toString());
            } catch (PathAlreadyExistsException ignored) {
                // 已存在的公共目录和任务目录可直接复用。
            }
        }
    }

    private String replacePaths(String json, Map<String, String> replacements) {
        if (!StringUtils.hasText(json) || replacements.isEmpty()) {
            return json;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            return objectMapper.writeValueAsString(replacePaths(root, replacements));
        } catch (Exception e) {
            throw new IllegalStateException("复制成果 JSON 失败：" + e.getMessage(), e);
        }
    }

    private JsonNode replacePaths(JsonNode node, Map<String, String> replacements) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isTextual()) {
            String replacement = replacements.get(node.asText());
            return replacement != null ? TextNode.valueOf(replacement) : node;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            List<Map.Entry<String, JsonNode>> snapshot = new ArrayList<>();
            fields.forEachRemaining(snapshot::add);
            for (Map.Entry<String, JsonNode> field : snapshot) {
                object.set(field.getKey(), replacePaths(field.getValue(), replacements));
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, replacePaths(array.get(i), replacements));
            }
        }
        return node;
    }

    private MathVisionSquareItemVO toItemVO(MathVisionSquarePost post, Long currentUserId) {
        return MathVisionSquareItemVO.builder()
                .shareId(post.getId())
                .taskId(post.getTaskId())
                .version(post.getVersion())
                .title(post.getTitle())
                .summary(post.getSummary())
                .authorName(StringUtils.hasText(post.getAuthorName()) ? post.getAuthorName() : "匿名用户")
                .outputTarget(post.getOutputTarget())
                .artifactPath(post.getArtifactPath())
                .artifactType(post.getArtifactType())
                .loadCount(post.getLoadCount() == null ? 0 : post.getLoadCount())
                .mine(post.getOwnerUserId() != null && post.getOwnerUserId().equals(currentUserId))
                .createTime(post.getCreateTime() != null ? post.getCreateTime().format(TS) : null)
                .build();
    }

    private MathVisionTask requireOwnedTask(Long taskId, Long userId) {
        MathVisionTask task = taskMapper.findById(taskId);
        if (task == null || !userId.equals(task.getUserId())) {
            throw new IllegalArgumentException("任务不存在或无权限访问");
        }
        return task;
    }

    private Long requireCurrentUserId() {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        if (userId == null) {
            throw new IllegalArgumentException("用户未登录");
        }
        return userId;
    }

    private String normalizeFilter(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String limit(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String extensionOf(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot) : "";
    }

    private void registerRollbackCleanup(List<String> paths) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    cleanupCopiedPaths(paths);
                }
            }
        });
    }

    private void cleanupCopiedPaths(List<String> paths) {
        for (int i = paths.size() - 1; i >= 0; i--) {
            try {
                fileStorageService.deleteFileObject(paths.get(i));
            } catch (Exception cleanupError) {
                log.warn("清理广场加载的临时文件失败, path={}, error={}",
                        paths.get(i), cleanupError.getMessage());
            }
        }
        paths.clear();
    }
}
