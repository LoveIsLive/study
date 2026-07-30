package com.kwang.study.mathvision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.dto.FileItem;
import com.kwang.study.fs.dto.result.FileObjectResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.llm.mapper.ChatMemoryMapper;
import com.kwang.study.llm.mapper.ChatSessionMapper;
import com.kwang.study.llm.pojo.ChatMemory;
import com.kwang.study.llm.pojo.ChatSession;
import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import com.kwang.study.mathvision.config.MathVisionModelCatalog.ModelCatalog;
import com.kwang.study.mathvision.config.MathVisionModelCatalog.ProviderCatalog;
import com.kwang.study.mathvision.controller.MathVisionFileUploadController;
import com.kwang.study.mathvision.dto.InputAssetDTO;
import com.kwang.study.mathvision.dto.MathVisionTaskCreateRequestDTO;
import com.kwang.study.mathvision.dto.MathVisionTaskCreateResponseDTO;
import com.kwang.study.mathvision.dto.MathVisionTaskDetailVO;
import com.kwang.study.mathvision.dto.MathVisionTaskItemVO;
import com.kwang.study.mathvision.dto.MathVisionTaskRuntimeSettingsRequestDTO;
import com.kwang.study.mathvision.dto.MathVisionTaskTitleUpdateRequestDTO;
import com.kwang.study.mathvision.dto.MathVisionVersionDetailVO;
import com.kwang.study.mathvision.dto.MathVisionVersionItemVO;
import com.kwang.study.mathvision.dto.PageResultVO;
import com.kwang.study.mathvision.dto.StageConfirmRequestDTO;
import com.kwang.study.mathvision.dto.StageAutoEditRequestDTO;
import com.kwang.study.mathvision.dto.StageContentSaveRequestDTO;
import com.kwang.study.mathvision.dto.StageDataVO;
import com.kwang.study.mathvision.dto.StageOperationResultVO;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.mapper.LlmModelConfigMapper;
import com.kwang.study.mathvision.mapper.MathVisionArtifactMapper;
import com.kwang.study.mathvision.mapper.MathVisionStageResultMapper;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.LlmModelConfig;
import com.kwang.study.mathvision.pojo.MathVisionArtifact;
import com.kwang.study.mathvision.pojo.MathVisionStageResult;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.pojo.MathVisionVersion;
import com.kwang.study.mathvision.workflow.model.CodeResult;
import com.kwang.study.mathvision.workflow.model.KnowledgeGraph;
import com.kwang.study.mathvision.workflow.model.Narrative;
import com.kwang.study.mathvision.workflow.model.ProblemBundle;
import com.kwang.study.mathvision.workflow.model.RenderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * MathVision 任务创建 / 列表 / 详情服务。
 * 任务运行由 MathVisionTaskScheduler 统一消费 mathvision_tasks 中的 queued 记录。
 */
@Service
public class MathVisionTaskService {

    private static final Logger log = LoggerFactory.getLogger(MathVisionTaskService.class);
    private static final String PURPOSE = "mathvision";
    private static final Pattern OWNED_UPLOAD_PATH = Pattern.compile(
            "^/mathvision/[0-9a-fA-F-]{36}(?:\\.[^/]+)?$");
    private static final java.time.format.DateTimeFormatter TS =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMemoryMapper chatMemoryMapper;
    private final MathVisionTaskMapper taskMapper;
    private final MathVisionVersionMapper versionMapper;
    private final MathVisionArtifactMapper artifactMapper;
    private final MathVisionStageResultMapper stageResultMapper;
    private final LlmModelConfigMapper configMapper;
    private final MathVisionModelCatalog catalog;
    private final FileStorageService fileStorageService;
    private final MathVisionFileUploadController uploadController;
    private final MathVisionTaskNotifier taskNotifier;
    private final ObjectMapper objectMapper;
    private final Path renderOutputRoot;

    public MathVisionTaskService(ChatSessionMapper chatSessionMapper,
                                 ChatMemoryMapper chatMemoryMapper,
                                 MathVisionTaskMapper taskMapper,
                                 MathVisionVersionMapper versionMapper,
                                 MathVisionArtifactMapper artifactMapper,
                                 MathVisionStageResultMapper stageResultMapper,
                                 LlmModelConfigMapper configMapper,
                                 MathVisionModelCatalog catalog,
                                 FileStorageService fileStorageService,
                                 MathVisionFileUploadController uploadController,
                                 MathVisionTaskNotifier taskNotifier,
                                 ObjectMapper objectMapper,
                                 @Value("${mathvision.render.output-root:mathvision-runs}") String renderOutputRoot) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMemoryMapper = chatMemoryMapper;
        this.taskMapper = taskMapper;
        this.versionMapper = versionMapper;
        this.artifactMapper = artifactMapper;
        this.stageResultMapper = stageResultMapper;
        this.configMapper = configMapper;
        this.catalog = catalog;
        this.fileStorageService = fileStorageService;
        this.uploadController = uploadController;
        this.taskNotifier = taskNotifier;
        this.objectMapper = objectMapper;
        this.renderOutputRoot = Paths.get(renderOutputRoot).toAbsolutePath().normalize();
    }

    @Transactional
    public MathVisionTaskCreateResponseDTO createTask(MathVisionTaskCreateRequestDTO request,
                                                      List<MultipartFile> smallFiles) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();

        // 幂等: 同 requestId 已创建则直接返回
        if (StringUtils.hasText(request.getRequestId())) {
            MathVisionTask exist = taskMapper.findByRequestId(request.getRequestId());
            if (exist != null) {
                return toCreateResponse(exist, exist.getStatus());
            }
        }

        // 处理输入资产 (小文件 + 大文件引用)
        List<InputAssetDTO> assets = collectAssets(smallFiles, request.getUploadFiles());
        boolean hasImage = assets.stream().anyMatch(a -> a.getMimeTypeName() != null
                && a.getMimeTypeName().startsWith("image/"));

        // 校验模型合法性 + 图片任务视觉能力
        validateModel(request.getProviderCode(), request.getModelName(), hasImage);

        // 解析或创建会话
        String sessionId = resolveSession(request, userId);

        // 记录用户可见输入
        saveUserInputMemory(sessionId, userId, request.getMessage(), assets);

        // 落任务主表
        LlmModelConfig cfg = configMapper.findByOwnerAndProvider(userId, request.getProviderCode());
        boolean autoStart = Boolean.TRUE.equals(request.getAutoStart());
        String status = autoStart ? "queued" : "created";

        MathVisionTask task = MathVisionTask.builder()
                .sessionId(sessionId)
                .userId(userId)
                .inputText(request.getMessage())
                .inputSourceType(resolveSourceType(request.getInputSourceType(), assets, request.getMessage()))
                .inputAssetsJson(writeJson(assets))
                .mode(request.getMode())
                .outputTarget(request.getOutputTarget())
                .status(status)
                .currentStage(StageEnum.PROBLEM_NORMALIZATION.getCode())
                .selectedModelConfigId(cfg != null ? cfg.getId() : null)
                .providerCode(request.getProviderCode())
                .modelName(request.getModelName())
                .currentVersion(1)
                .requestId(request.getRequestId())
                .build();
        taskMapper.insert(task);

        // 建 V1 任务版本 (各阶段指针为空, 尚未生成)
        MathVisionVersion v1 = MathVisionVersion.builder()
                .taskId(task.getId())
                .version(1)
                .changeSource("initial_generation")
                .changeSummary("初始生成")
                .isCurrent(true)
                .build();
        versionMapper.insert(v1);
        taskNotifier.notifyTaskChanged(task.getId(), status);

        return toCreateResponse(task, status);
    }
    /** 小文件落存储 + 大文件引用, 统一转 InputAssetDTO 列表。 */
    private List<InputAssetDTO> collectAssets(List<MultipartFile> smallFiles,
                                              List<MathVisionTaskCreateRequestDTO.FileNameAndPath> uploadFiles) {
        List<InputAssetDTO> assets = new ArrayList<>();
        if (!CollectionUtils.isEmpty(smallFiles)) {
            for (MultipartFile f : smallFiles) {
                if (f == null || f.isEmpty()) {
                    continue;
                }
                String fileName = f.getOriginalFilename();
                String path = uploadController.produceFilePath(fileName);
                try (InputStream in = f.getInputStream()) {
                    fileStorageService.createFile(path, in, f.getContentType());
                    FileObjectResult obj = fileStorageService.getFileObject(path);
                    assets.add(InputAssetDTO.builder()
                            .fileName(fileName)
                            .filePath(path)
                            .mimeTypeName(obj.getMimeTypeName())
                            .fileSize(obj.getSize())
                            .source("multipart")
                            .build());
                } catch (Exception e) {
                    throw new RuntimeException("文件保存失败: " + fileName, e);
                }
            }
        }
        if (!CollectionUtils.isEmpty(uploadFiles)) {
            for (MathVisionTaskCreateRequestDTO.FileNameAndPath ref : uploadFiles) {
                try {
                    FileObjectResult obj = fileStorageService.getFileObject(ref.getFilePath());
                    assets.add(InputAssetDTO.builder()
                            .fileName(ref.getFileName())
                            .filePath(ref.getFilePath())
                            .mimeTypeName(obj.getMimeTypeName())
                            .fileSize(obj.getSize())
                            .source("uploadFiles")
                            .build());
                } catch (Exception e) {
                    throw new RuntimeException("文件读取失败: " + ref.getFileName(), e);
                }
            }
        }
        return assets;
    }

    /** 校验模型属于目录, 且图片任务必须选支持视觉的模型。 */
    private void validateModel(String providerCode, String modelName, boolean hasImage) {
        ProviderCatalog provider = catalog.findEnabled(providerCode);
        if (provider == null) {
            throw new IllegalArgumentException("不支持或未启用的模型厂家: " + providerCode);
        }
        ModelCatalog model = catalog.findModel(providerCode, modelName);
        if (model == null) {
            throw new IllegalArgumentException("该厂家下不存在可用模型: " + modelName);
        }
        if (hasImage && !Boolean.TRUE.equals(model.getSupportVision())) {
            throw new IllegalArgumentException("所选模型不支持图片输入, 请更换支持视觉的模型");
        }
        if (model.getContextWindow() == null || model.getContextWindow() <= 0
                || model.getMaxOutputTokens() == null || model.getMaxOutputTokens() <= 0) {
            throw new IllegalArgumentException("Nacos math-vision 模型上下文配置不完整: " + modelName);
        }
        Double temperature = model.getTemperature() != null
                ? model.getTemperature()
                : (provider.getTemperature() != null
                ? provider.getTemperature()
                : catalog.getModelDefaults().getTemperature());
        if (!"anthropic".equalsIgnoreCase(providerCode) && temperature == null) {
            throw new IllegalArgumentException("Nacos math-vision 模型缺少 temperature: " + modelName);
        }
    }

    /** sessionId 为空则新建 chat_session; 非空则校验归属与 purpose。 */
    private String resolveSession(MathVisionTaskCreateRequestDTO request, Long userId) {
        String sessionId = request.getSessionId();
        if (!StringUtils.hasText(sessionId)) {
            sessionId = UUID.randomUUID().toString();
            ChatSession session = ChatSession.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .title(resolveTitle(request))
                    .purpose(PURPOSE)
                    .build();
            chatSessionMapper.insert(session);
            return sessionId;
        }
        ChatSession existing = chatSessionMapper.findBySessionId(sessionId);
        if (existing == null) {
            ChatSession session = ChatSession.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .title(resolveTitle(request))
                    .purpose(PURPOSE)
                    .build();
            chatSessionMapper.insert(session);
            return sessionId;
        }
        if (!userId.equals(existing.getUserId())) {
            throw new IllegalArgumentException("无权使用该会话");
        }
        if (!PURPOSE.equals(existing.getPurpose())) {
            throw new IllegalArgumentException("会话用途不匹配");
        }
        return sessionId;
    }

    private String resolveTitle(MathVisionTaskCreateRequestDTO request) {
        if (StringUtils.hasText(request.getTitle())) {
            return request.getTitle();
        }
        String msg = request.getMessage();
        if (StringUtils.hasText(msg)) {
            return msg.length() > 30 ? msg.substring(0, 30) : msg;
        }
        return "教学动画生成任务";
    }

    /** 写一条用户可见的输入记录到 chat_memory。 */
    private void saveUserInputMemory(String sessionId, Long userId, String message, List<InputAssetDTO> assets) {
        StringBuilder content = new StringBuilder(message == null ? "" : message);
        if (!assets.isEmpty()) {
            content.append("\n[附件: ");
            for (int i = 0; i < assets.size(); i++) {
                if (i > 0) content.append(", ");
                content.append(assets.get(i).getFileName());
            }
            content.append("]");
        }
        ChatMemory memory = ChatMemory.builder()
                .sessionId(sessionId)
                .userId(userId)
                .role("user")
                .type("text")
                .content(content.toString())
                .build();
        chatMemoryMapper.insert(memory);
    }

    private String resolveSourceType(String declared, List<InputAssetDTO> assets, String message) {
        if (StringUtils.hasText(declared)) {
            return declared;
        }
        boolean hasImage = assets.stream().anyMatch(a -> a.getMimeTypeName() != null
                && a.getMimeTypeName().startsWith("image/"));
        boolean hasText = StringUtils.hasText(message);
        if (hasImage && hasText) return "mixed";
        if (hasImage) return "image";
        return "text";
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("序列化 input_assets_json 失败: {}", e.getMessage());
            return "[]";
        }
    }

    private MathVisionTaskCreateResponseDTO toCreateResponse(MathVisionTask task, String status) {
        return MathVisionTaskCreateResponseDTO.builder()
                .taskId(task.getId())
                .sessionId(task.getSessionId())
                .title(null)
                .status(status)
                .currentStage(task.getCurrentStage())
                .mode(task.getMode())
                .outputTarget(task.getOutputTarget())
                .providerCode(task.getProviderCode())
                .modelName(task.getModelName())
                .currentVersion(task.getCurrentVersion())
                .autoStart("queued".equals(status))
                .build();
    }

    /** 分页查询当前用户任务列表。 */
    public PageResultVO<MathVisionTaskItemVO> listTasks(String keyword, String status, String outputTarget,
                                                        int page, int size) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 10 : size;
        int offset = (p - 1) * s;
        List<MathVisionTask> tasks = taskMapper.pageList(userId, keyword, status, outputTarget, offset, s);
        long total = taskMapper.countList(userId, keyword, status, outputTarget);
        List<MathVisionTaskItemVO> records = new ArrayList<>();
        for (MathVisionTask t : tasks) {
            ChatSession session = chatSessionMapper.findBySessionId(t.getSessionId());
            records.add(toItemVO(t, session));
        }
        return PageResultVO.<MathVisionTaskItemVO>builder().records(records).total(total).build();
    }

    /** 分页查询当前用户回收站中的任务。 */
    public PageResultVO<MathVisionTaskItemVO> listDeletedTasks(String keyword, int page, int size) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 10 : size;
        int offset = (p - 1) * s;
        List<MathVisionTask> tasks = taskMapper.pageDeletedList(userId, keyword, offset, s);
        long total = taskMapper.countDeletedList(userId, keyword);
        List<MathVisionTaskItemVO> records = new ArrayList<>();
        for (MathVisionTask task : tasks) {
            ChatSession session = chatSessionMapper.findBySessionId(task.getSessionId());
            records.add(toItemVO(task, session));
        }
        return PageResultVO.<MathVisionTaskItemVO>builder().records(records).total(total).build();
    }

    /** 获取任务的用户可见版本列表，最新版本在前。 */
    public List<MathVisionVersionItemVO> listTaskVersions(Long taskId) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findOwnedTask(taskId, userId);
        List<MathVisionVersionItemVO> result = new ArrayList<>();
        for (MathVisionVersion version : versionMapper.findByTask(task.getId())) {
            RenderMetadata render = resolveRenderMetadata(task.getId(), version.getRrVersion());
            result.add(MathVisionVersionItemVO.builder()
                    .version(version.getVersion())
                    .baseVersion(version.getBaseVersion())
                    .branchStage(version.getBranchStage())
                    .latestStage(lastSuccessfulStage(version))
                    .changeSource(version.getChangeSource())
                    .changeSummary(version.getChangeSummary())
                    .finalArtifactType(render.success ? render.artifactType : null)
                    .isCurrent(version.getVersion() != null
                            && version.getVersion().equals(task.getCurrentVersion()))
                    .createTime(formatTime(version.getCreateTime()))
                    .updateTime(formatTime(version.getUpdateTime()))
                    .build());
        }
        return result;
    }

    /** 获取指定任务版本的完整阶段快照。 */
    public MathVisionVersionDetailVO getTaskVersionDetail(Long taskId, Integer versionNumber) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findOwnedTask(taskId, userId);
        MathVisionVersion version = requireTaskVersion(task.getId(), versionNumber);

        String problemBundleJson = artifactJson(task.getId(), StageEnum.PROBLEM_NORMALIZATION, version.getPnVersion());
        String dagGraphJson = artifactJson(task.getId(), StageEnum.REASONING_GRAPH, version.getRgVersion());
        String narrativeJson = artifactJson(task.getId(), StageEnum.VISUAL_STORYBOARD, version.getVsVersion());
        String codeJson = artifactJson(task.getId(), StageEnum.CODE_GENERATION, version.getCgVersion());
        String renderJson = artifactJson(task.getId(), StageEnum.RENDER_RESULT, version.getRrVersion());
        CodeResult codeResult = readJson(codeJson, CodeResult.class);
        RenderMetadata render = resolveRenderMetadata(task.getId(), version.getRrVersion());

        return MathVisionVersionDetailVO.builder()
                .taskId(task.getId())
                .version(version.getVersion())
                .baseVersion(version.getBaseVersion())
                .branchStage(version.getBranchStage())
                .latestStage(lastSuccessfulStage(version))
                .changeSource(version.getChangeSource())
                .changeSummary(version.getChangeSummary())
                .isCurrent(version.getVersion() != null
                        && version.getVersion().equals(task.getCurrentVersion()))
                .problemNormalizationVersion(version.getPnVersion())
                .reasoningGraphVersion(version.getRgVersion())
                .visualStoryboardVersion(version.getVsVersion())
                .codeGenerationVersion(version.getCgVersion())
                .renderResultVersion(version.getRrVersion())
                .problemBundleJson(problemBundleJson)
                .dagGraphJson(dagGraphJson)
                .narrativeJson(narrativeJson)
                .codeText(codeResult != null ? codeResult.getGeneratedCode() : null)
                .codeFormat(codeResult != null ? codeResult.getArtifactFormat() : null)
                .renderResultJson(renderJson)
                .artifactPath(render.success ? render.artifactPath : null)
                .finalArtifactType(render.success ? render.artifactType : null)
                .workflowSummaryJson(version.getWorkflowSummaryJson())
                .createTime(formatTime(version.getCreateTime()))
                .updateTime(formatTime(version.getUpdateTime()))
                .build();
    }

    /** 激活历史版本，并同步任务的阶段、状态和最终产物指针。 */
    @Transactional
    public MathVisionTaskDetailVO activateTaskVersion(Long taskId, Integer versionNumber) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findOwnedTask(taskId, userId);
        if ("queued".equals(task.getStatus()) || "running".equals(task.getStatus())
                || Boolean.TRUE.equals(task.getCancelRequested())) {
            throw new IllegalArgumentException("任务正在执行或取消中，不能切换版本");
        }
        MathVisionVersion target = requireTaskVersion(task.getId(), versionNumber);
        if (versionNumber.equals(task.getCurrentVersion())) {
            return getTaskDetail(taskId);
        }

        VersionActivationState state = resolveVersionActivationState(task.getId(), target);
        versionMapper.clearCurrent(task.getId());
        if (versionMapper.setCurrent(task.getId(), target.getVersion()) == 0) {
            throw new IllegalArgumentException("目标版本不存在，请刷新后重试");
        }
        int updated = taskMapper.activateVersionState(
                task.getId(),
                userId,
                target.getVersion(),
                state.status,
                state.currentStage,
                state.lastConfirmedStage,
                state.failedStage,
                state.errorType,
                state.errorMessage,
                state.finalArtifactPath,
                state.finalArtifactType);
        if (updated == 0) {
            throw new IllegalArgumentException("任务状态已变化，请刷新后重试");
        }
        saveVisibleMemory(task.getSessionId(), task.getUserId(), "切换任务版本至 V" + target.getVersion());
        taskNotifier.notifyTaskChanged(task.getId(), "version_activated");
        return getTaskDetail(taskId);
    }

    /** 任务详情; 校验归属。 */
    public MathVisionTaskDetailVO getTaskDetail(Long taskId) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = taskMapper.findById(taskId);
        if (task == null || !userId.equals(task.getUserId())) {
            throw new IllegalArgumentException("任务不存在或无权限访问");
        }
        ChatSession session = chatSessionMapper.findBySessionId(task.getSessionId());
        List<InputAssetDTO> assets = readAssets(task.getInputAssetsJson());
        return MathVisionTaskDetailVO.builder()
                .taskId(task.getId())
                .sessionId(task.getSessionId())
                .title(session != null ? session.getTitle() : null)
                .inputText(task.getInputText())
                .inputSourceType(task.getInputSourceType())
                .inputAssets(assets)
                .mode(task.getMode())
                .outputTarget(task.getOutputTarget())
                .status(task.getStatus())
                .currentStage(task.getCurrentStage())
                .failedStage(task.getFailedStage())
                .errorType(task.getErrorType())
                .errorMessage(task.getErrorMessage())
                .selectedModelConfigId(task.getSelectedModelConfigId())
                .providerCode(task.getProviderCode())
                .modelName(task.getModelName())
                .currentVersion(task.getCurrentVersion())
                .lastConfirmedStage(task.getLastConfirmedStage())
                .cancelRequested(task.getCancelRequested())
                .finalArtifactPath(task.getFinalArtifactPath())
                .finalArtifactType(task.getFinalArtifactType())
                .createTime(task.getCreateTime() != null ? task.getCreateTime().format(TS) : null)
                .updateTime(task.getUpdateTime() != null ? task.getUpdateTime().format(TS) : null)
                .build();
    }

    @Transactional
    public MathVisionTaskDetailVO updateRuntimeSettings(
            Long taskId,
            MathVisionTaskRuntimeSettingsRequestDTO request) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findOwnedTask(taskId, userId);

        boolean updateMode = StringUtils.hasText(request.getMode());
        boolean hasProvider = StringUtils.hasText(request.getProviderCode());
        boolean hasModel = StringUtils.hasText(request.getModelName());
        if (!updateMode && !hasProvider && !hasModel) {
            throw new IllegalArgumentException("请至少修改运行模式或模型");
        }
        if (hasProvider != hasModel) {
            throw new IllegalArgumentException("切换模型时必须同时提供模型厂家和模型名称");
        }

        String mode = updateMode ? request.getMode().trim().toLowerCase() : task.getMode();
        if (!"auto".equals(mode) && !"manual".equals(mode)) {
            throw new IllegalArgumentException("运行模式必须是 auto 或 manual");
        }

        Long selectedModelConfigId = task.getSelectedModelConfigId();
        String providerCode = task.getProviderCode();
        String modelName = task.getModelName();
        if (hasProvider) {
            providerCode = request.getProviderCode().trim();
            modelName = request.getModelName().trim();
            boolean hasImage = readAssets(task.getInputAssetsJson()).stream()
                    .anyMatch(asset -> StringUtils.hasText(asset.getMimeTypeName())
                            && asset.getMimeTypeName().startsWith("image/"));
            validateModel(providerCode, modelName, hasImage);
            LlmModelConfig config = configMapper.findByOwnerAndProvider(userId, providerCode);
            if (config == null || !StringUtils.hasText(config.getApiKeyEncrypted())) {
                throw new IllegalArgumentException("请先配置所选模型厂家的 API Key");
            }
            if (StringUtils.hasText(config.getStatus())
                    && !"enabled".equalsIgnoreCase(config.getStatus())) {
                throw new IllegalArgumentException("所选模型厂家的凭证当前不可用");
            }
            selectedModelConfigId = config.getId();
        }

        if (taskMapper.updateRuntimeSettings(
                task.getId(), userId, mode, selectedModelConfigId, providerCode, modelName) == 0) {
            throw new IllegalArgumentException("任务状态已变化，请刷新后重试");
        }

        boolean switchedToAuto = updateMode
                && "auto".equals(mode)
                && !"auto".equals(task.getMode());
        if (switchedToAuto && "waiting_confirm".equals(task.getStatus())) {
            return startTask(taskId);
        }

        taskNotifier.notifyTaskChanged(task.getId(), "runtime_settings_updated");
        return getTaskDetail(taskId);
    }

    @Transactional
    public MathVisionTaskDetailVO updateTaskTitle(
            Long taskId,
            MathVisionTaskTitleUpdateRequestDTO request) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findOwnedTask(taskId, userId);
        String title = request.getTitle() != null ? request.getTitle().trim() : "";
        if (!StringUtils.hasText(title)) {
            throw new IllegalArgumentException("任务标题不能为空");
        }
        if (title.length() > 255) {
            throw new IllegalArgumentException("任务标题不能超过 255 个字符");
        }
        if (chatSessionMapper.updateTitle(task.getSessionId(), title) == 0) {
            throw new IllegalArgumentException("任务会话不存在，请刷新后重试");
        }
        taskNotifier.notifyTaskChanged(task.getId(), "title_updated");
        return getTaskDetail(taskId);
    }

    public StageDataVO getStageData(Long taskId, String stageCode) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findOwnedTask(taskId, userId);
        String resolvedStage = StringUtils.hasText(stageCode) ? stageCode : task.getCurrentStage();
        StageEnum stage = StageEnum.fromCode(resolvedStage);
        if (stage == null || StageEnum.COMPLETED.equals(stage)) {
            throw new IllegalArgumentException("不支持的阶段: " + resolvedStage);
        }
        CurrentStagePayload payload = resolveStagePayload(task, resolvedStage);
        boolean hasArtifact = StringUtils.hasText(payload.artifactJson);
        boolean isCurrentStage = resolvedStage.equals(task.getCurrentStage());
        boolean running = "queued".equals(task.getStatus()) || "running".equals(task.getStatus());
        boolean editable = hasArtifact
                && !running
                && !StageEnum.RENDER_RESULT.getCode().equals(resolvedStage);
        boolean canAutoEdit = editable && !Boolean.TRUE.equals(task.getCancelRequested());
        boolean canRegenerate = hasArtifact
                && !running
                && !Boolean.TRUE.equals(task.getCancelRequested());
        return StageDataVO.builder()
                .taskId(task.getId())
                .sessionId(task.getSessionId())
                .status(task.getStatus())
                .currentStage(task.getCurrentStage())
                .currentVersion(task.getCurrentVersion())
                .stage(resolvedStage)
                .stageVersion(payload.version)
                .artifactJson(payload.artifactJson)
                .resultJson(payload.resultJson)
                .editable(editable)
                .canConfirm(hasArtifact && isCurrentStage && "waiting_confirm".equals(task.getStatus()))
                .canRegenerate(canRegenerate)
                .canAutoEdit(canAutoEdit)
                .build();
    }

    @Transactional
    public StageOperationResultVO saveStageContent(Long taskId,
                                                   String stageCode,
                                                   StageContentSaveRequestDTO request) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findOwnedTask(taskId, userId);
        StageEnum stage = requireEditableStage(stageCode);
        if ("queued".equals(task.getStatus()) || "running".equals(task.getStatus())) {
            throw new IllegalArgumentException("任务正在执行中，不能保存阶段编辑");
        }
        if (Boolean.TRUE.equals(task.getCancelRequested())) {
            throw new IllegalArgumentException("任务正在取消中，不能保存阶段编辑");
        }

        MathVisionVersion version = currentVersion(task);
        if (version == null) {
            throw new IllegalArgumentException("当前任务版本不存在");
        }
        Integer currentStageVersion = stageVersionOf(version, stage.getCode());
        if (currentStageVersion == null) {
            throw new IllegalArgumentException("当前版本没有该阶段产物，不能编辑: " + stage.getCode());
        }
        if (!currentStageVersion.equals(request.getVersion())) {
            throw new IllegalArgumentException("阶段版本已变化，请刷新后再保存");
        }
        MathVisionArtifact artifact = artifactMapper.findByTaskStageVersion(
                task.getId(), stage.getCode(), currentStageVersion);
        if (artifact == null || !StringUtils.hasText(artifact.getArtifactJson())) {
            throw new IllegalArgumentException("阶段产物不存在或为空，不能编辑: " + stage.getCode());
        }

        String artifactJson = normalizeStageContent(task, stage, request.getContent(), artifact);
        String changeSummary = StringUtils.hasText(request.getComment())
                ? trimSummary(request.getComment())
                : "manual structured edit for " + stage.getCode();
        boolean copyOnWrite = shouldCopyOnWrite(task, version, stage, currentStageVersion);
        Integer savedStageVersion = currentStageVersion;
        Integer currentTaskVersion = task.getCurrentVersion();

        if (copyOnWrite) {
            savedStageVersion = insertManualEditedArtifact(task, stage, currentStageVersion, artifactJson, changeSummary);
            currentTaskVersion = insertManualEditedTaskVersion(task, version, stage, savedStageVersion, changeSummary);
            taskMapper.updateCurrentVersion(task.getId(), currentTaskVersion);
        } else {
            MathVisionArtifact update = MathVisionArtifact.builder()
                    .id(artifact.getId())
                    .artifactJson(artifactJson)
                    .changeSource("manual_edit")
                    .changeSummary(changeSummary)
                    .build();
            artifactMapper.updateArtifactJson(update);
        }

        String lastConfirmedStage = previousStageCode(stage);
        taskMapper.updateManualEditState(task.getId(), "waiting_confirm", stage.getCode(), lastConfirmedStage);
        saveVisibleMemory(task.getSessionId(), task.getUserId(),
                "手动编辑阶段 " + stage.getCode() + "，阶段版本 V" + savedStageVersion
                        + (StringUtils.hasText(request.getComment()) ? "：" + request.getComment().trim() : ""));
        taskNotifier.notifyTaskChanged(task.getId(), "stage_saved");

        return StageOperationResultVO.builder()
                .taskId(task.getId())
                .status("waiting_confirm")
                .currentStage(stage.getCode())
                .currentVersion(currentTaskVersion)
                .stage(stage.getCode())
                .stageVersion(savedStageVersion)
                .lastConfirmedStage(lastConfirmedStage)
                .saved(true)
                .copyOnWrite(copyOnWrite)
                .build();
    }

    @Transactional
    public StageOperationResultVO autoEditStage(Long taskId,
                                                String stageCode,
                                                StageAutoEditRequestDTO request) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findOwnedTask(taskId, userId);
        StageEnum stage = requireEditableStage(stageCode);
        if ("queued".equals(task.getStatus()) || "running".equals(task.getStatus())) {
            throw new IllegalArgumentException("任务正在执行中，不能提交自动编辑");
        }
        if (Boolean.TRUE.equals(task.getCancelRequested())) {
            throw new IllegalArgumentException("任务正在取消中，不能提交自动编辑");
        }

        MathVisionVersion sourceVersion = currentVersion(task);
        if (sourceVersion == null) {
            throw new IllegalArgumentException("当前任务版本不存在");
        }
        Integer currentStageVersion = stageVersionOf(sourceVersion, stage.getCode());
        if (currentStageVersion == null) {
            throw new IllegalArgumentException("当前版本没有该阶段产物，不能自动编辑: " + stage.getCode());
        }
        if (!currentStageVersion.equals(request.getBaseStageVersion())) {
            throw new IllegalArgumentException("阶段版本已变化，请刷新后重新提交自动编辑");
        }
        MathVisionArtifact baseline = artifactMapper.findByTaskStageVersion(
                task.getId(), stage.getCode(), currentStageVersion);
        if (baseline == null || !StringUtils.hasText(baseline.getArtifactJson())) {
            throw new IllegalArgumentException("阶段产物不存在或为空，不能自动编辑: " + stage.getCode());
        }

        String instruction = request.getInstruction().trim();
        String changeSummary = trimSummary("user revision for " + stage.getCode() + ": " + instruction);
        Integer maxTaskVersion = versionMapper.findMaxVersion(task.getId());
        int nextTaskVersion = maxTaskVersion == null ? 1 : maxTaskVersion + 1;
        MathVisionVersion revisionVersion = MathVisionVersion.builder()
                .taskId(task.getId())
                .version(nextTaskVersion)
                .baseVersion(sourceVersion.getVersion())
                .pnVersion(sourceVersion.getPnVersion())
                .rgVersion(sourceVersion.getRgVersion())
                .vsVersion(sourceVersion.getVsVersion())
                .cgVersion(sourceVersion.getCgVersion())
                .rrVersion(sourceVersion.getRrVersion())
                .branchStage(stage.getCode())
                .changeSource("user_revision")
                .changeSummary(changeSummary)
                .changeInstruction(instruction)
                .isCurrent(true)
                .build();
        clearDownstreamPointers(revisionVersion, stage);

        versionMapper.clearCurrent(task.getId());
        versionMapper.insert(revisionVersion);
        String lastConfirmedStage = previousStageCode(stage);
        int queued = taskMapper.queueAutoEdit(
                task.getId(),
                userId,
                nextTaskVersion,
                stage.getCode(),
                lastConfirmedStage);
        if (queued == 0) {
            throw new IllegalArgumentException("任务状态已变化，请刷新后重新提交自动编辑");
        }

        saveVisibleMemory(task.getSessionId(), task.getUserId(),
                "提交阶段 " + stage.getCode() + " 自动编辑，基线阶段版本 V" + currentStageVersion
                        + "：" + instruction);
        taskNotifier.notifyTaskChanged(task.getId(), "auto_edit_queued");

        return StageOperationResultVO.builder()
                .taskId(task.getId())
                .status("queued")
                .currentStage(stage.getCode())
                .currentVersion(nextTaskVersion)
                .stage(stage.getCode())
                .stageVersion(currentStageVersion)
                .lastConfirmedStage(lastConfirmedStage)
                .saved(true)
                .copyOnWrite(true)
                .build();
    }

    @Transactional
    public MathVisionTaskDetailVO confirmStage(Long taskId,
                                               String stageCode,
                                               StageConfirmRequestDTO request) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findOwnedTask(taskId, userId);
        StageEnum stage = StageEnum.fromCode(stageCode);
        if (stage == null || StageEnum.COMPLETED.equals(stage)) {
            throw new IllegalArgumentException("不支持的阶段: " + stageCode);
        }
        if (!"waiting_confirm".equals(task.getStatus())) {
            throw new IllegalArgumentException("当前任务不在待确认状态");
        }
        if (!stage.getCode().equals(task.getCurrentStage())) {
            throw new IllegalArgumentException("只能确认当前阶段: " + task.getCurrentStage());
        }
        MathVisionVersion version = currentVersion(task);
        Integer stageVersion = version != null ? stageVersionOf(version, stage.getCode()) : null;
        if (stageVersion == null || !stageVersion.equals(request.getVersion())) {
            throw new IllegalArgumentException("阶段版本已变化，请刷新后再确认");
        }
        MathVisionArtifact artifact = artifactMapper.findByTaskStageVersion(
                task.getId(), stage.getCode(), stageVersion);
        if (artifact == null || !StringUtils.hasText(artifact.getArtifactJson())) {
            throw new IllegalArgumentException("阶段产物不存在或为空，不能确认");
        }
        saveVisibleMemory(task.getSessionId(), task.getUserId(),
                "确认阶段 " + stage.getCode() + "，阶段版本 V" + stageVersion
                        + (StringUtils.hasText(request.getComment()) ? "：" + request.getComment().trim() : ""));
        return startTask(taskId);
    }

    /** 当前用户手动启动/继续任务; 每次只提交一个用户可见阶段。 */
    @Transactional
    public MathVisionTaskDetailVO startTask(Long taskId) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findOwnedTask(taskId, userId);
        if ("queued".equals(task.getStatus()) || "running".equals(task.getStatus())) {
            return getTaskDetail(taskId);
        }
        if ("completed".equals(task.getStatus())) {
            throw new IllegalArgumentException("任务已完成");
        }
        if ("failed".equals(task.getStatus())) {
            return retryFailedTaskStage(taskId, userId, task, null);
        }

        String stageToRun = task.getCurrentStage();
        String lastConfirmedStage = null;
        if (!StringUtils.hasText(stageToRun)) {
            stageToRun = StageEnum.PROBLEM_NORMALIZATION.getCode();
        }
        if ("canceled".equals(task.getStatus())) {
            ResumePoint resumePoint = resolveCanceledResumePoint(task);
            if (resumePoint.completed) {
                taskMapper.markCompleted(taskId);
                taskNotifier.notifyTaskChanged(taskId, "completed");
                return getTaskDetail(taskId);
            }
            stageToRun = resumePoint.stageToRun;
            lastConfirmedStage = resumePoint.lastSuccessfulStage;
        } else if ("waiting_confirm".equals(task.getStatus())) {
            if (StageEnum.RENDER_RESULT.getCode().equals(stageToRun)) {
                taskMapper.markCompleted(taskId);
                taskNotifier.notifyTaskChanged(taskId, "completed");
                return getTaskDetail(taskId);
            }
            lastConfirmedStage = stageToRun;
            stageToRun = nextStageCode(stageToRun);
        }
        if (!isRunnableStage(stageToRun)) {
            throw new IllegalArgumentException("当前阶段不可启动: " + stageToRun);
        }

        int updated = taskMapper.queueTaskForRun(taskId, userId, stageToRun, lastConfirmedStage);
        if (updated == 0) {
            throw new IllegalArgumentException("当前任务状态不可启动");
        }
        taskNotifier.notifyTaskChanged(taskId, "queued");
        return getTaskDetail(taskId);
    }

    @Transactional
    public MathVisionTaskDetailVO retryTaskStage(Long taskId, String stageCode) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findOwnedTask(taskId, userId);
        if (!"failed".equals(task.getStatus())) {
            throw new IllegalArgumentException("当前任务不在失败状态，不能重试指定阶段");
        }
        return retryFailedTaskStage(taskId, userId, task, stageCode);
    }

    @Transactional
    public MathVisionTaskDetailVO regenerateTaskStage(Long taskId, String stageCode) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findOwnedTask(taskId, userId);
        if ("queued".equals(task.getStatus()) || "running".equals(task.getStatus())) {
            throw new IllegalArgumentException("任务正在执行中，不能重新生成阶段");
        }
        if (Boolean.TRUE.equals(task.getCancelRequested())) {
            throw new IllegalArgumentException("任务正在取消中，不能重新生成阶段");
        }
        StageEnum stage = StageEnum.fromCode(stageCode);
        if (stage == null || StageEnum.COMPLETED.equals(stage) || !isRunnableStage(stage.getCode())) {
            throw new IllegalArgumentException("不支持重新生成阶段: " + stageCode);
        }

        MathVisionVersion sourceVersion = currentVersion(task);
        if (sourceVersion == null) {
            throw new IllegalArgumentException("当前任务版本不存在");
        }
        Integer sourceStageVersion = stageVersionOf(sourceVersion, stage.getCode());
        if (sourceStageVersion == null) {
            throw new IllegalArgumentException("当前版本没有该阶段产物，不能重新生成: " + stage.getCode());
        }
        ensureRetryStagePrerequisites(sourceVersion, stage);

        Integer maxTaskVersion = versionMapper.findMaxVersion(task.getId());
        int nextTaskVersion = maxTaskVersion == null ? 1 : maxTaskVersion + 1;
        MathVisionVersion regenerated = MathVisionVersion.builder()
                .taskId(task.getId())
                .version(nextTaskVersion)
                .baseVersion(sourceVersion.getVersion())
                .pnVersion(sourceVersion.getPnVersion())
                .rgVersion(sourceVersion.getRgVersion())
                .vsVersion(sourceVersion.getVsVersion())
                .cgVersion(sourceVersion.getCgVersion())
                .rrVersion(sourceVersion.getRrVersion())
                .branchStage(stage.getCode())
                .changeSource("regenerate")
                .changeSummary("regenerate stage " + stage.getCode() + " and downstream stages")
                .isCurrent(true)
                .build();
        setStagePointer(regenerated, stage, null);
        clearDownstreamPointers(regenerated, stage);

        versionMapper.clearCurrent(task.getId());
        versionMapper.insert(regenerated);
        String lastConfirmedStage = previousStageCode(stage);
        int queued = taskMapper.queueRegenerateVersion(
                task.getId(),
                userId,
                nextTaskVersion,
                stage.getCode(),
                lastConfirmedStage);
        if (queued == 0) {
            throw new IllegalArgumentException("任务状态已变化，请刷新后重新生成");
        }

        saveVisibleMemory(task.getSessionId(), task.getUserId(),
                "从阶段 " + stage.getCode() + " 重新生成当前及后续阶段，来源任务版本 V"
                        + sourceVersion.getVersion() + "，来源阶段版本 V" + sourceStageVersion);
        log.info("MathVision stage regeneration queued, taskId={}, sourceTaskVersion={}, newTaskVersion={}, "
                        + "stage={}, sourceStageVersion={}, lastConfirmedStage={}",
                task.getId(), sourceVersion.getVersion(), nextTaskVersion,
                stage.getCode(), sourceStageVersion, lastConfirmedStage);
        taskNotifier.notifyTaskChanged(task.getId(), "regenerate_queued");
        return getTaskDetail(taskId);
    }

    private MathVisionTaskDetailVO retryFailedTaskStage(Long taskId,
                                                        Long userId,
                                                        MathVisionTask task,
                                                        String requestedStageCode) {
        StageEnum retryStage = resolveRetryStage(task, requestedStageCode);
        if (retryStage == null || !isRunnableStage(retryStage.getCode())) {
            throw new IllegalArgumentException("当前阶段不可启动: " + task.getCurrentStage());
        }
        MathVisionVersion sourceVersion = currentVersion(task);
        if (sourceVersion == null) {
            throw new IllegalArgumentException("当前任务版本不存在");
        }
        ensureRetryStagePrerequisites(sourceVersion, retryStage);

        Integer previousStageVersion = stageVersionOf(sourceVersion, retryStage.getCode());
        clearDownstreamPointers(sourceVersion, retryStage);
        versionMapper.updateStagePointers(sourceVersion);

        String lastConfirmedStage = previousStageCode(retryStage);
        int queued = taskMapper.queueRetryVersion(
                task.getId(),
                userId,
                sourceVersion.getVersion(),
                retryStage.getCode(),
                lastConfirmedStage);
        if (queued == 0) {
            throw new IllegalArgumentException("任务状态已变化，请刷新后重试");
        }

        log.info("MathVision retry stage queued in current version, taskId={}, taskVersion={}, "
                        + "stage={}, lastConfirmedStage={}, previousStageVersion={}, failedStage={}, errorType={}, errorMessage={}",
                task.getId(),
                sourceVersion.getVersion(),
                retryStage.getCode(),
                lastConfirmedStage,
                previousStageVersion,
                task.getFailedStage(),
                task.getErrorType(),
                trimSummary(task.getErrorMessage()));
        taskNotifier.notifyTaskChanged(task.getId(), "retry_queued");
        return getTaskDetail(taskId);
    }

    /** 当前用户取消任务。排队/待确认直接取消; 运行中写取消请求, 由执行器在阶段边界收敛。 */
    @Transactional
    public MathVisionTaskDetailVO cancelTask(Long taskId) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findOwnedTask(taskId, userId);
        if ("completed".equals(task.getStatus()) || "canceled".equals(task.getStatus())) {
            return getTaskDetail(taskId);
        }
        int updated = taskMapper.cancelIdleTask(taskId, userId);
        if (updated == 0) {
            updated = taskMapper.requestCancelRunning(taskId, userId);
            if (updated > 0) {
                taskNotifier.notifyTaskChanged(taskId, "cancel_requested");
            }
        } else {
            taskNotifier.notifyTaskChanged(taskId, "canceled");
        }
        if (updated == 0) {
            throw new IllegalArgumentException("当前任务状态不可取消");
        }
        return getTaskDetail(taskId);
    }

    /** 将空闲任务移入回收站。排队中或运行中的任务必须先取消。 */
    @Transactional
    public void deleteTask(Long taskId) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findOwnedTask(taskId, userId);
        if ("queued".equals(task.getStatus()) || "running".equals(task.getStatus())) {
            throw new IllegalArgumentException("任务正在执行，请先取消任务再删除");
        }
        int updated = taskMapper.softDelete(taskId, userId);
        if (updated == 0) {
            throw new IllegalArgumentException("任务状态已变化，请刷新后重试");
        }
    }

    /** 从回收站恢复任务，保留删除前的任务状态和全部阶段产物。 */
    @Transactional
    public MathVisionTaskDetailVO restoreTask(Long taskId) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findDeletedTask(taskId, userId);
        int updated = taskMapper.restore(task.getId(), userId);
        if (updated == 0) {
            throw new IllegalArgumentException("任务已恢复或不存在");
        }
        taskNotifier.notifyTaskChanged(taskId, "restored");
        return getTaskDetail(taskId);
    }

    /** 永久删除回收站任务及其版本、阶段产物、对话记录和存储文件。 */
    @Transactional
    public void permanentlyDeleteTask(Long taskId) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        MathVisionTask task = findDeletedTask(taskId, userId);
        List<String> inputPaths = collectOwnedInputPaths(task.getInputAssetsJson());

        stageResultMapper.deleteByTaskId(taskId);
        artifactMapper.deleteByTaskId(taskId);
        versionMapper.deleteByTaskId(taskId);
        int deleted = taskMapper.hardDelete(taskId, userId);
        if (deleted == 0) {
            throw new IllegalArgumentException("任务已恢复或不存在");
        }
        chatMemoryMapper.deleteBySessionId(task.getSessionId());
        chatSessionMapper.deleteBySessionId(task.getSessionId());
        scheduleStorageCleanup(taskId, inputPaths);
    }

    private MathVisionTaskItemVO toItemVO(MathVisionTask t, ChatSession session) {
        return MathVisionTaskItemVO.builder()
                .taskId(t.getId())
                .sessionId(t.getSessionId())
                .title(session != null ? session.getTitle() : null)
                .status(t.getStatus())
                .currentStage(t.getCurrentStage())
                .mode(t.getMode())
                .outputTarget(t.getOutputTarget())
                .providerCode(t.getProviderCode())
                .modelName(t.getModelName())
                .cancelRequested(t.getCancelRequested())
                .finalArtifactType(t.getFinalArtifactType())
                .squareShareId(t.getSquareShareId())
                .createTime(t.getCreateTime() != null ? t.getCreateTime().format(TS) : null)
                .updateTime(t.getUpdateTime() != null ? t.getUpdateTime().format(TS) : null)
                .build();
    }

    private MathVisionTask findOwnedTask(Long taskId, Long userId) {
        MathVisionTask task = taskMapper.findById(taskId);
        if (task == null || !userId.equals(task.getUserId())) {
            throw new IllegalArgumentException("任务不存在或无权限访问");
        }
        return task;
    }

    private MathVisionTask findDeletedTask(Long taskId, Long userId) {
        MathVisionTask task = taskMapper.findDeletedById(taskId, userId);
        if (task == null) {
            throw new IllegalArgumentException("回收站任务不存在或无权限访问");
        }
        return task;
    }

    private List<String> collectOwnedInputPaths(String inputAssetsJson) {
        Set<String> paths = new LinkedHashSet<>();
        for (InputAssetDTO asset : readAssets(inputAssetsJson)) {
            String path = asset.getFilePath();
            if (StringUtils.hasText(path) && OWNED_UPLOAD_PATH.matcher(path).matches()) {
                paths.add(path);
            }
        }
        return new ArrayList<>(paths);
    }

    private void scheduleStorageCleanup(Long taskId, List<String> inputPaths) {
        Runnable cleanup = () -> cleanupTaskStorage(taskId, inputPaths);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
            return;
        }
        cleanup.run();
    }

    private void cleanupTaskStorage(Long taskId, List<String> inputPaths) {
        for (String path : inputPaths) {
            try {
                fileStorageService.deleteFileObject(path);
            } catch (Exception e) {
                log.warn("永久删除 MathVision 输入文件失败, taskId={}, path={}, error={}",
                        taskId, path, e.getMessage());
            }
        }
        String taskRoot = "/mathvision/task-" + taskId;
        try {
            fileStorageService.deleteDirObject(taskRoot);
        } catch (Exception e) {
            log.debug("MathVision 任务产物目录无需清理或清理失败, taskId={}, path={}, error={}",
                    taskId, taskRoot, e.getMessage());
        }
        cleanupLocalRenderDirectory(taskId);
    }

    private void cleanupLocalRenderDirectory(Long taskId) {
        Path taskDirectory = renderOutputRoot.resolve("task-" + taskId).toAbsolutePath().normalize();
        if (!taskDirectory.startsWith(renderOutputRoot)
                || taskDirectory.equals(renderOutputRoot)
                || !Files.exists(taskDirectory)) {
            return;
        }
        try (var paths = Files.walk(taskDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    log.warn("永久删除 MathVision 本地渲染文件失败, taskId={}, path={}, error={}",
                            taskId, path, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("永久删除 MathVision 本地渲染目录失败, taskId={}, path={}, error={}",
                    taskId, taskDirectory, e.getMessage());
        }
    }

    private StageEnum requireEditableStage(String stageCode) {
        StageEnum stage = StageEnum.fromCode(stageCode);
        if (stage == null || StageEnum.COMPLETED.equals(stage)) {
            throw new IllegalArgumentException("不支持的阶段: " + stageCode);
        }
        if (StageEnum.RENDER_RESULT.equals(stage)) {
            throw new IllegalArgumentException("渲染结果阶段不支持结构化编辑，请返回前置阶段修改");
        }
        return stage;
    }

    private boolean shouldCopyOnWrite(MathVisionTask task,
                                      MathVisionVersion version,
                                      StageEnum stage,
                                      Integer stageVersion) {
        if (isStageConfirmed(task, stage)) {
            return true;
        }
        return versionMapper.countStageReferences(task.getId(), stage.getCode(), stageVersion) > 1;
    }

    private boolean isStageConfirmed(MathVisionTask task, StageEnum stage) {
        if ("completed".equals(task.getStatus())) {
            return true;
        }
        int stageIndex = stageIndex(stage.getCode());
        int confirmedIndex = stageIndex(task.getLastConfirmedStage());
        int currentIndex = stageIndex(task.getCurrentStage());
        return confirmedIndex >= stageIndex || currentIndex > stageIndex;
    }

    private String normalizeStageContent(MathVisionTask task,
                                         StageEnum stage,
                                         JsonNode content,
                                         MathVisionArtifact currentArtifact) {
        if (content == null || content.isNull()) {
            throw new IllegalArgumentException("保存内容不能为空");
        }
        try {
            if (StageEnum.PROBLEM_NORMALIZATION.equals(stage)) {
                ProblemBundle bundle = objectMapper.treeToValue(content, ProblemBundle.class);
                return toPrettyJson(bundle);
            }
            if (StageEnum.REASONING_GRAPH.equals(stage)) {
                KnowledgeGraph graph = objectMapper.treeToValue(content, KnowledgeGraph.class);
                return toPrettyJson(graph);
            }
            if (StageEnum.VISUAL_STORYBOARD.equals(stage)) {
                Narrative narrative = objectMapper.treeToValue(content, Narrative.class);
                return toPrettyJson(narrative);
            }
            if (StageEnum.CODE_GENERATION.equals(stage)) {
                CodeResult codeResult;
                if (content.isTextual()) {
                    codeResult = objectMapper.readValue(currentArtifact.getArtifactJson(), CodeResult.class);
                    codeResult.setGeneratedCode(content.asText(""));
                } else {
                    codeResult = objectMapper.treeToValue(content, CodeResult.class);
                }
                if (!StringUtils.hasText(codeResult.getOutputTarget())) {
                    codeResult.setOutputTarget(StringUtils.hasText(task.getOutputTarget()) ? task.getOutputTarget() : "manim");
                }
                return toPrettyJson(codeResult);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("阶段内容结构不合法: " + e.getMessage(), e);
        }
        throw new IllegalArgumentException("不支持的阶段: " + stage.getCode());
    }

    private Integer insertManualEditedArtifact(MathVisionTask task,
                                               StageEnum stage,
                                               Integer baseStageVersion,
                                               String artifactJson,
                                               String changeSummary) {
        Integer maxVersion = artifactMapper.findMaxVersion(task.getId(), stage.getCode());
        int nextStageVersion = maxVersion == null ? 1 : maxVersion + 1;
        MathVisionArtifact edited = MathVisionArtifact.builder()
                .taskId(task.getId())
                .sessionId(task.getSessionId())
                .userId(task.getUserId())
                .stage(stage.getCode())
                .version(nextStageVersion)
                .baseVersion(baseStageVersion)
                .artifactJson(artifactJson)
                .changeSource("manual_edit")
                .changeSummary(changeSummary)
                .build();
        artifactMapper.insert(edited);
        return nextStageVersion;
    }

    private Integer insertManualEditedTaskVersion(MathVisionTask task,
                                                  MathVisionVersion source,
                                                  StageEnum stage,
                                                  Integer stageVersion,
                                                  String changeSummary) {
        Integer maxTaskVersion = versionMapper.findMaxVersion(task.getId());
        int nextTaskVersion = maxTaskVersion == null ? 1 : maxTaskVersion + 1;
        MathVisionVersion edited = MathVisionVersion.builder()
                .taskId(task.getId())
                .version(nextTaskVersion)
                .baseVersion(source.getVersion())
                .pnVersion(source.getPnVersion())
                .rgVersion(source.getRgVersion())
                .vsVersion(source.getVsVersion())
                .cgVersion(source.getCgVersion())
                .rrVersion(source.getRrVersion())
                .branchStage(stage.getCode())
                .changeSource("manual_edit")
                .changeSummary(changeSummary)
                .isCurrent(true)
                .build();
        setStagePointer(edited, stage, stageVersion);
        clearDownstreamPointers(edited, stage);
        versionMapper.clearCurrent(task.getId());
        versionMapper.insert(edited);
        return nextTaskVersion;
    }

    private void setStagePointer(MathVisionVersion version, StageEnum stage, Integer stageVersion) {
        if (StageEnum.PROBLEM_NORMALIZATION.equals(stage)) {
            version.setPnVersion(stageVersion);
        } else if (StageEnum.REASONING_GRAPH.equals(stage)) {
            version.setRgVersion(stageVersion);
        } else if (StageEnum.VISUAL_STORYBOARD.equals(stage)) {
            version.setVsVersion(stageVersion);
        } else if (StageEnum.CODE_GENERATION.equals(stage)) {
            version.setCgVersion(stageVersion);
        } else if (StageEnum.RENDER_RESULT.equals(stage)) {
            version.setRrVersion(stageVersion);
        }
    }

    private void clearDownstreamPointers(MathVisionVersion version, StageEnum stage) {
        if (StageEnum.PROBLEM_NORMALIZATION.equals(stage)) {
            version.setRgVersion(null);
            version.setVsVersion(null);
            version.setCgVersion(null);
            version.setRrVersion(null);
        } else if (StageEnum.REASONING_GRAPH.equals(stage)) {
            version.setVsVersion(null);
            version.setCgVersion(null);
            version.setRrVersion(null);
        } else if (StageEnum.VISUAL_STORYBOARD.equals(stage)) {
            version.setCgVersion(null);
            version.setRrVersion(null);
        } else if (StageEnum.CODE_GENERATION.equals(stage)) {
            version.setRrVersion(null);
        }
    }

    private CurrentStagePayload resolveStagePayload(MathVisionTask task, String stage) {
        MathVisionVersion version = currentVersion(task);
        if (version == null) {
            return CurrentStagePayload.empty();
        }
        Integer stageVersion = stageVersionOf(version, stage);
        if (stageVersion == null) {
            return CurrentStagePayload.empty();
        }
        MathVisionArtifact artifact = artifactMapper.findByTaskStageVersion(task.getId(), stage, stageVersion);
        if (artifact == null) {
            return CurrentStagePayload.empty();
        }
        // The workbench only renders and edits the stage artifact. Stage result JSON can contain
        // hundreds of KB of AI trace prompts and repair conversations, so loading it here makes
        // every task/stage click transfer diagnostic data that the UI never displays. Keep the
        // full result in storage for workflow diagnostics and load it only in diagnostic paths.
        return new CurrentStagePayload(stageVersion, artifact.getArtifactJson(), null);
    }

    private void saveVisibleMemory(String sessionId, Long userId, String content) {
        ChatMemory memory = ChatMemory.builder()
                .sessionId(sessionId)
                .userId(userId)
                .role("user")
                .type("text")
                .content(content)
                .build();
        chatMemoryMapper.insert(memory);
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    private String trimSummary(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }

    private String previousStageCode(StageEnum stage) {
        if (StageEnum.PROBLEM_NORMALIZATION.equals(stage)) {
            return null;
        }
        if (StageEnum.REASONING_GRAPH.equals(stage)) {
            return StageEnum.PROBLEM_NORMALIZATION.getCode();
        }
        if (StageEnum.VISUAL_STORYBOARD.equals(stage)) {
            return StageEnum.REASONING_GRAPH.getCode();
        }
        if (StageEnum.CODE_GENERATION.equals(stage)) {
            return StageEnum.VISUAL_STORYBOARD.getCode();
        }
        if (StageEnum.RENDER_RESULT.equals(stage)) {
            return StageEnum.CODE_GENERATION.getCode();
        }
        return null;
    }

    private StageEnum resolveRetryStage(MathVisionTask task, String requestedStageCode) {
        if (StringUtils.hasText(requestedStageCode)) {
            StageEnum requestedStage = StageEnum.fromCode(requestedStageCode);
            if (requestedStage == null || StageEnum.COMPLETED.equals(requestedStage)) {
                throw new IllegalArgumentException("不支持重试阶段: " + requestedStageCode);
            }
            String failureBoundary = StringUtils.hasText(task.getFailedStage())
                    ? task.getFailedStage() : task.getCurrentStage();
            if (stageIndex(requestedStage.getCode()) > stageIndex(failureBoundary)) {
                throw new IllegalArgumentException("不能从失败阶段之后重试: " + requestedStageCode);
            }
            return requestedStage;
        }
        StageEnum failedStage = StageEnum.fromCode(task.getFailedStage());
        if (failedStage != null && !StageEnum.COMPLETED.equals(failedStage)) {
            return failedStage;
        }
        StageEnum currentStage = StageEnum.fromCode(task.getCurrentStage());
        if (currentStage != null && !StageEnum.COMPLETED.equals(currentStage)) {
            return currentStage;
        }
        return null;
    }

    private String retrySummary(StageEnum stage, MathVisionTask task) {
        StringBuilder summary = new StringBuilder("retry failed stage ")
                .append(stage.getCode());
        if (StringUtils.hasText(task.getErrorType())) {
            summary.append(": ").append(task.getErrorType());
        }
        if (StringUtils.hasText(task.getErrorMessage())) {
            summary.append(" - ").append(task.getErrorMessage().trim());
        }
        return trimSummary(summary.toString());
    }

    private void ensureRetryStagePrerequisites(MathVisionVersion version, StageEnum stage) {
        if (StageEnum.PROBLEM_NORMALIZATION.equals(stage)) {
            return;
        }
        if (version.getPnVersion() == null) {
            throw new IllegalArgumentException("缺少题目识别产物，不能重试阶段: " + stage.getCode());
        }
        if (StageEnum.REASONING_GRAPH.equals(stage)) {
            return;
        }
        if (version.getRgVersion() == null) {
            throw new IllegalArgumentException("缺少解题步骤产物，不能重试阶段: " + stage.getCode());
        }
        if (StageEnum.VISUAL_STORYBOARD.equals(stage)) {
            return;
        }
        if (version.getVsVersion() == null) {
            throw new IllegalArgumentException("缺少讲解脚本产物，不能重试阶段: " + stage.getCode());
        }
        if (StageEnum.CODE_GENERATION.equals(stage)) {
            return;
        }
        if (version.getCgVersion() == null) {
            throw new IllegalArgumentException("缺少代码产物，不能重试阶段: " + stage.getCode());
        }
    }

    private int stageIndex(String stage) {
        if (StageEnum.PROBLEM_NORMALIZATION.getCode().equals(stage)) {
            return 0;
        }
        if (StageEnum.REASONING_GRAPH.getCode().equals(stage)) {
            return 1;
        }
        if (StageEnum.VISUAL_STORYBOARD.getCode().equals(stage)) {
            return 2;
        }
        if (StageEnum.CODE_GENERATION.getCode().equals(stage)) {
            return 3;
        }
        if (StageEnum.RENDER_RESULT.getCode().equals(stage)) {
            return 4;
        }
        if (StageEnum.COMPLETED.getCode().equals(stage)) {
            return 5;
        }
        return -1;
    }

    private ResumePoint resolveCanceledResumePoint(MathVisionTask task) {
        MathVisionVersion version = currentVersion(task);
        String lastSuccessfulStage = lastSuccessfulStage(version);
        if (!StringUtils.hasText(lastSuccessfulStage)) {
            return ResumePoint.resume(StageEnum.PROBLEM_NORMALIZATION.getCode(), null);
        }
        if (StageEnum.RENDER_RESULT.getCode().equals(lastSuccessfulStage)) {
            return ResumePoint.completed();
        }
        return ResumePoint.resume(nextStageCode(lastSuccessfulStage), lastSuccessfulStage);
    }

    private MathVisionVersion currentVersion(MathVisionTask task) {
        if (task == null) {
            return null;
        }
        MathVisionVersion version = versionMapper.findCurrent(task.getId());
        if (version == null && task.getCurrentVersion() != null) {
            version = versionMapper.findByTaskVersion(task.getId(), task.getCurrentVersion());
        }
        return version;
    }

    private String lastSuccessfulStage(MathVisionVersion version) {
        if (version == null) {
            return null;
        }
        if (version.getRrVersion() != null) {
            return StageEnum.RENDER_RESULT.getCode();
        }
        if (version.getCgVersion() != null) {
            return StageEnum.CODE_GENERATION.getCode();
        }
        if (version.getVsVersion() != null) {
            return StageEnum.VISUAL_STORYBOARD.getCode();
        }
        if (version.getRgVersion() != null) {
            return StageEnum.REASONING_GRAPH.getCode();
        }
        if (version.getPnVersion() != null) {
            return StageEnum.PROBLEM_NORMALIZATION.getCode();
        }
        return null;
    }

    private String nextStageCode(String currentStage) {
        if (StageEnum.PROBLEM_NORMALIZATION.getCode().equals(currentStage)) {
            return StageEnum.REASONING_GRAPH.getCode();
        }
        if (StageEnum.REASONING_GRAPH.getCode().equals(currentStage)) {
            return StageEnum.VISUAL_STORYBOARD.getCode();
        }
        if (StageEnum.VISUAL_STORYBOARD.getCode().equals(currentStage)) {
            return StageEnum.CODE_GENERATION.getCode();
        }
        if (StageEnum.CODE_GENERATION.getCode().equals(currentStage)) {
            return StageEnum.RENDER_RESULT.getCode();
        }
        throw new IllegalArgumentException("当前阶段不可继续: " + currentStage);
    }

    private boolean isRunnableStage(String stage) {
        return StageEnum.PROBLEM_NORMALIZATION.getCode().equals(stage)
                || StageEnum.REASONING_GRAPH.getCode().equals(stage)
                || StageEnum.VISUAL_STORYBOARD.getCode().equals(stage)
                || StageEnum.CODE_GENERATION.getCode().equals(stage)
                || StageEnum.RENDER_RESULT.getCode().equals(stage);
    }

    private Integer stageVersionOf(MathVisionVersion version, String stage) {
        if (StageEnum.PROBLEM_NORMALIZATION.getCode().equals(stage)) {
            return version.getPnVersion();
        }
        if (StageEnum.REASONING_GRAPH.getCode().equals(stage)) {
            return version.getRgVersion();
        }
        if (StageEnum.VISUAL_STORYBOARD.getCode().equals(stage)) {
            return version.getVsVersion();
        }
        if (StageEnum.CODE_GENERATION.getCode().equals(stage)) {
            return version.getCgVersion();
        }
        if (StageEnum.RENDER_RESULT.getCode().equals(stage)) {
            return version.getRrVersion();
        }
        return null;
    }

    private MathVisionVersion requireTaskVersion(Long taskId, Integer versionNumber) {
        if (versionNumber == null || versionNumber < 1) {
            throw new IllegalArgumentException("版本号不合法");
        }
        MathVisionVersion version = versionMapper.findByTaskVersion(taskId, versionNumber);
        if (version == null) {
            throw new IllegalArgumentException("任务版本不存在: V" + versionNumber);
        }
        return version;
    }

    private String artifactJson(Long taskId, StageEnum stage, Integer stageVersion) {
        if (stageVersion == null) {
            return null;
        }
        MathVisionArtifact artifact = artifactMapper.findByTaskStageVersion(
                taskId, stage.getCode(), stageVersion);
        return artifact != null ? artifact.getArtifactJson() : null;
    }

    private JsonNode resultJson(Long taskId, StageEnum stage, Integer stageVersion) {
        if (stageVersion == null) {
            return null;
        }
        MathVisionStageResult result = stageResultMapper.findByTaskStageVersion(
                taskId, stage.getCode(), stageVersion);
        if (result == null || !StringUtils.hasText(result.getResultJson())) {
            return null;
        }
        try {
            return objectMapper.readTree(result.getResultJson());
        } catch (Exception e) {
            log.warn("解析版本阶段结果失败, taskId={}, stage={}, version={}: {}",
                    taskId, stage.getCode(), stageVersion, e.getMessage());
            return null;
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("解析版本产物失败, type={}: {}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private RenderMetadata resolveRenderMetadata(Long taskId, Integer renderVersion) {
        if (renderVersion == null) {
            return RenderMetadata.empty();
        }
        String renderJson = artifactJson(taskId, StageEnum.RENDER_RESULT, renderVersion);
        RenderResult render = readJson(renderJson, RenderResult.class);
        JsonNode result = resultJson(taskId, StageEnum.RENDER_RESULT, renderVersion);
        boolean success = render != null && render.isSuccess();
        if (result != null && result.has("success")) {
            success = result.path("success").asBoolean(false);
        }
        String path = render != null ? render.getArtifactPath() : null;
        String type = render != null ? render.getArtifactType() : null;
        String error = render != null ? render.getLastError() : null;
        if (result != null) {
            if (!StringUtils.hasText(path)) {
                path = result.path("artifactPath").asText(null);
            }
            if (!StringUtils.hasText(type)) {
                type = result.path("artifactType").asText(null);
            }
            if (!StringUtils.hasText(error)) {
                error = result.path("sceneEvaluation").path("gateReason").asText(null);
            }
        }
        return new RenderMetadata(success, path, type, error);
    }

    private VersionActivationState resolveVersionActivationState(Long taskId, MathVisionVersion version) {
        if (version.getRrVersion() != null) {
            RenderMetadata render = resolveRenderMetadata(taskId, version.getRrVersion());
            if (render.success) {
                return VersionActivationState.completed(render.artifactPath, render.artifactType);
            }
            return VersionActivationState.failed(
                    StageEnum.RENDER_RESULT,
                    "render_error",
                    StringUtils.hasText(render.errorMessage) ? render.errorMessage : "该版本渲染未成功");
        }

        if (version.getCgVersion() != null) {
            // Code evaluation becomes advisory after its fix budget is exhausted; rendering is next.
            return VersionActivationState.waiting(StageEnum.RENDER_RESULT);
        }
        if (version.getVsVersion() != null) {
            return VersionActivationState.waiting(StageEnum.VISUAL_STORYBOARD);
        }
        if (version.getRgVersion() != null) {
            return VersionActivationState.waiting(StageEnum.REASONING_GRAPH);
        }
        if (version.getPnVersion() != null) {
            return VersionActivationState.waiting(StageEnum.PROBLEM_NORMALIZATION);
        }
        return VersionActivationState.created();
    }

    private String formatTime(java.time.LocalDateTime value) {
        return value != null ? value.format(TS) : null;
    }

    private static final class RenderMetadata {
        private final boolean success;
        private final String artifactPath;
        private final String artifactType;
        private final String errorMessage;

        private RenderMetadata(boolean success, String artifactPath, String artifactType, String errorMessage) {
            this.success = success;
            this.artifactPath = artifactPath;
            this.artifactType = artifactType;
            this.errorMessage = errorMessage;
        }

        private static RenderMetadata empty() {
            return new RenderMetadata(false, null, null, null);
        }
    }

    private static final class VersionActivationState {
        private final String status;
        private final String currentStage;
        private final String lastConfirmedStage;
        private final String failedStage;
        private final String errorType;
        private final String errorMessage;
        private final String finalArtifactPath;
        private final String finalArtifactType;

        private VersionActivationState(String status,
                                       String currentStage,
                                       String lastConfirmedStage,
                                       String failedStage,
                                       String errorType,
                                       String errorMessage,
                                       String finalArtifactPath,
                                       String finalArtifactType) {
            this.status = status;
            this.currentStage = currentStage;
            this.lastConfirmedStage = lastConfirmedStage;
            this.failedStage = failedStage;
            this.errorType = errorType;
            this.errorMessage = errorMessage;
            this.finalArtifactPath = finalArtifactPath;
            this.finalArtifactType = finalArtifactType;
        }

        private static VersionActivationState created() {
            return new VersionActivationState(
                    "created", StageEnum.PROBLEM_NORMALIZATION.getCode(), null,
                    null, null, null, null, null);
        }

        private static VersionActivationState waiting(StageEnum stage) {
            String previous = null;
            if (StageEnum.REASONING_GRAPH.equals(stage)) {
                previous = StageEnum.PROBLEM_NORMALIZATION.getCode();
            } else if (StageEnum.VISUAL_STORYBOARD.equals(stage)) {
                previous = StageEnum.REASONING_GRAPH.getCode();
            } else if (StageEnum.CODE_GENERATION.equals(stage)) {
                previous = StageEnum.VISUAL_STORYBOARD.getCode();
            } else if (StageEnum.RENDER_RESULT.equals(stage)) {
                previous = StageEnum.CODE_GENERATION.getCode();
            }
            return new VersionActivationState(
                    "waiting_confirm", stage.getCode(), previous,
                    null, null, null, null, null);
        }

        private static VersionActivationState failed(StageEnum stage, String errorType, String errorMessage) {
            VersionActivationState waiting = waiting(stage);
            return new VersionActivationState(
                    "failed", stage.getCode(), waiting.lastConfirmedStage,
                    stage.getCode(), errorType, errorMessage, null, null);
        }

        private static VersionActivationState completed(String artifactPath, String artifactType) {
            return new VersionActivationState(
                    "completed", StageEnum.COMPLETED.getCode(), StageEnum.RENDER_RESULT.getCode(),
                    null, null, null, artifactPath, artifactType);
        }
    }

    private static final class CurrentStagePayload {
        private final Integer version;
        private final String artifactJson;
        private final String resultJson;

        private CurrentStagePayload(Integer version, String artifactJson, String resultJson) {
            this.version = version;
            this.artifactJson = artifactJson;
            this.resultJson = resultJson;
        }

        private static CurrentStagePayload empty() {
            return new CurrentStagePayload(null, null, null);
        }
    }

    private static final class ResumePoint {
        private final boolean completed;
        private final String stageToRun;
        private final String lastSuccessfulStage;

        private ResumePoint(boolean completed, String stageToRun, String lastSuccessfulStage) {
            this.completed = completed;
            this.stageToRun = stageToRun;
            this.lastSuccessfulStage = lastSuccessfulStage;
        }

        private static ResumePoint resume(String stageToRun, String lastSuccessfulStage) {
            return new ResumePoint(false, stageToRun, lastSuccessfulStage);
        }

        private static ResumePoint completed() {
            return new ResumePoint(true, null, StageEnum.RENDER_RESULT.getCode());
        }
    }

    private List<InputAssetDTO> readAssets(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, InputAssetDTO.class));
        } catch (Exception e) {
            log.warn("解析 input_assets_json 失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
