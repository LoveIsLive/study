package com.kwang.study.mathvision.service;

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
import com.kwang.study.mathvision.dto.PageResultVO;
import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.mapper.LlmModelConfigMapper;
import com.kwang.study.mathvision.mapper.MathVisionTaskMapper;
import com.kwang.study.mathvision.mapper.MathVisionVersionMapper;
import com.kwang.study.mathvision.pojo.LlmModelConfig;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.pojo.MathVisionVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MathVision 任务创建 / 列表 / 详情服务。
 * 本阶段只打通落库链路 (chat_session / chat_memory / task / 版本 V1),
 * 不接入真正的 workflow 执行。
 */
@Service
public class MathVisionTaskService {

    private static final Logger log = LoggerFactory.getLogger(MathVisionTaskService.class);
    private static final String PURPOSE = "mathvision";
    private static final java.time.format.DateTimeFormatter TS =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMemoryMapper chatMemoryMapper;
    private final MathVisionTaskMapper taskMapper;
    private final MathVisionVersionMapper versionMapper;
    private final LlmModelConfigMapper configMapper;
    private final MathVisionModelCatalog catalog;
    private final FileStorageService fileStorageService;
    private final MathVisionFileUploadController uploadController;
    private final ObjectMapper objectMapper;

    public MathVisionTaskService(ChatSessionMapper chatSessionMapper,
                                 ChatMemoryMapper chatMemoryMapper,
                                 MathVisionTaskMapper taskMapper,
                                 MathVisionVersionMapper versionMapper,
                                 LlmModelConfigMapper configMapper,
                                 MathVisionModelCatalog catalog,
                                 FileStorageService fileStorageService,
                                 MathVisionFileUploadController uploadController,
                                 ObjectMapper objectMapper) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMemoryMapper = chatMemoryMapper;
        this.taskMapper = taskMapper;
        this.versionMapper = versionMapper;
        this.configMapper = configMapper;
        this.catalog = catalog;
        this.fileStorageService = fileStorageService;
        this.uploadController = uploadController;
        this.objectMapper = objectMapper;
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

        // autoStart 的实际 workflow 提交留待引擎接入阶段; 此处仅置 queued 状态。
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
                .errorMessage(task.getErrorMessage())
                .selectedModelConfigId(task.getSelectedModelConfigId())
                .providerCode(task.getProviderCode())
                .modelName(task.getModelName())
                .currentVersion(task.getCurrentVersion())
                .lastConfirmedStage(task.getLastConfirmedStage())
                .finalArtifactPath(task.getFinalArtifactPath())
                .finalArtifactType(task.getFinalArtifactType())
                .createTime(task.getCreateTime() != null ? task.getCreateTime().format(TS) : null)
                .updateTime(task.getUpdateTime() != null ? task.getUpdateTime().format(TS) : null)
                .build();
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
                .finalArtifactType(t.getFinalArtifactType())
                .createTime(t.getCreateTime() != null ? t.getCreateTime().format(TS) : null)
                .updateTime(t.getUpdateTime() != null ? t.getUpdateTime().format(TS) : null)
                .build();
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
