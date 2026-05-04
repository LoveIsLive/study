package com.kwang.study.llm.service;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.lang.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.dto.FileItem;
import com.kwang.study.fs.dto.result.FileObjectResult;
import com.kwang.study.fs.dto.result.GenericObjectResult;
import com.kwang.study.fs.mapper.NodeMapper;
import com.kwang.study.fs.pojo.Node;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.llm.config.LLMGlobalConfig;
import com.kwang.study.llm.core.*;
import com.kwang.study.llm.dto.request.AIFileSummaryDTO;
import com.kwang.study.llm.dto.request.ChatRequestDTO;
import com.kwang.study.llm.dto.request.ContentPartMessage;
import com.kwang.study.llm.dto.response.MindGenResponseDTO;
import com.kwang.study.llm.mapper.ChatMemoryMapper;
import com.kwang.study.llm.mapper.ChatSessionMapper;
import com.kwang.study.llm.pojo.ChatMemory;
import com.kwang.study.llm.pojo.ChatSession;
import com.kwang.study.ware.mapper.NodeMetadataMapper;
import com.kwang.study.ware.pojo.NodeMetadata;
import com.kwang.study.ware.service.WareService;
import com.openai.models.chat.completions.ChatCompletionMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

@Service
@Slf4j
public class LLMService {
    @Autowired
    private LLMGlobalConfig llmGlobalConfig;
    @Autowired
    private ChatMemoryMapper chatMemoryMapper;
    @Autowired
    private ChatSessionMapper chatSessionMapper;
    @Autowired
    private Agent agent;
    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private FileStorageService fileStorageService;
    @Autowired
    private RAG rag;
    @Autowired
    private NodeMetadataMapper nodeMetadataMapper;
    @Autowired
    private WareService wareService;
    @Autowired
    private NodeMapper nodeMapper;

    /**
     * 生成新的会话ID
     */
    public String createSessionId(String purpose) {
        // 暂时不去做策略
        return UUID.randomUUID().toString();
    }

    public List<ChatSession> getUserSessions(String purpose) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        return chatSessionMapper.findByUserIdAndPurpose(userId, purpose);
    }

    /**
     * 构建request
     */
    private void buildRequest(ChatRequestDTO request) {
        request.setRequestId(UUID.randomUUID().toString());

        // 根据scene补充reques
        String scene = request.getScene();
        if ("file-summary".equals(scene)) {
            Map<String, Object> sceneParams = request.getSceneParams();
            Assert.notNull(sceneParams, "sceneParams is null");
            Assert.notNull(sceneParams.get("path"), "sceneParams path is null");
            String path = (String) sceneParams.get("path");
            try {
                String actualPath = wareService.buildActualPath(path);
                FileObjectResult fileObject = fileStorageService.getFileObject(actualPath);

                FileItem fileItem = FileItem.builder()
                        .fileName(fileObject.getName())
                        .mimeTypeName(fileObject.getMimeTypeName())
                        .fileSize(fileObject.getSize())
                        .path(actualPath)
                        .stream(fileObject.getContent()) // 直接透传输入流
                        .build();
                request.setContentPartMessage(ContentPartMessage.builder()
                        .text("请提取该文件的主要内容描述。")
                        .files(Collections.singletonList(fileItem))
                        .build());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 构建上下文辅助方法
     */
    private LLMContext buildContext(ChatRequestDTO request, Long userId) {
        LLMGlobalConfig.SceneConfig sceneConfig = llmGlobalConfig.getScenes().getOrDefault(request.getScene(),
                llmGlobalConfig.getScenes().get("default"));

        boolean existContentPart = request.getContentPartMessage() != null;
        if (!existContentPart) {
            List<ChatMemory> memories = chatMemoryMapper.findBySessionId(request.getSessionId());
            for (ChatMemory memory : memories) {
                if ("file".equals(memory.getType())) {
                    existContentPart = true;
                    break;
                }
            }
        }
        if (existContentPart) {
            LLMGlobalConfig.SceneConfig finalSceneConfig = new LLMGlobalConfig.SceneConfig();
            BeanUtils.copyProperties(sceneConfig, finalSceneConfig);

            LLMGlobalConfig.SceneConfig imageConfig = llmGlobalConfig.getScenes().getOrDefault("image",
                    llmGlobalConfig.getScenes().get("default"));
            finalSceneConfig.setModelName(imageConfig.getModelName());
            finalSceneConfig.setApiKey(imageConfig.getApiKey());
            finalSceneConfig.setBaseUrl(imageConfig.getBaseUrl());
            sceneConfig = finalSceneConfig;
        }

        String systemPrompt = "";
        try {
            systemPrompt = rag.build(request, sceneConfig.getSystemPromptTemplate());
        } catch (JsonProcessingException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

        return LLMContext.builder()
                .userId(userId)
                .sessionId(request.getSessionId())
                .scene(request.getScene())
                .sceneParams(request.getSceneParams())
                .llmConfig(sceneConfig)
                .request(request)
                .systemPrompt(systemPrompt)
                .build();
    }

    /**
     * 保存历史记录，如果会话还没创建，先创建会话记录
     * request和response有且仅能有一个不为null
     */
    @Transactional
    public Pair<ChatSession, ChatMemory> saveMemory(ChatMemory chatMemory, LLMContext context) {
        ChatSession session = chatSessionMapper.findBySessionId(chatMemory.getSessionId());
        if (session == null) {
            // 只有当这是第一条消息（通常是User发的）才创建，或者系统恢复时
            String title;
            String content = chatMemory.getContent();

            if ("user".equals(chatMemory.getRole())) {
                if ("text".equals(chatMemory.getType())) {
                    title = content.length() > 20 ? content.substring(0, 20) + "..." : content;
                } else if ("file".equals(chatMemory.getType())) {
                    try {
                        String text = objectMapper.readValue(content, ContentPartMessage.class).getText();
                        title = text.length() > 20 ? text.substring(0, 20) + "..." : text;
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    throw new IllegalArgumentException("用户输入类型错误");
                }
            } else {
                title = "新会话"; // 防止第一条是 AI 发的（极少情况）
            }

            String purpose = "chat_window";
            // 应当配合new_session的策略，根据session_id来判断是哪个purpose。这里只适配少数场景
            if ("homework-gen".equals(context.getScene())) {
                purpose = "homework-gen";
            } else if ("mind-block-gen".equals(context.getScene())) {
                purpose = "mind-block-gen";
            }

            ChatSession newSession = ChatSession.builder()
                    .sessionId(chatMemory.getSessionId())
                    .userId(chatMemory.getUserId())
                    .title(title)
                    .purpose(purpose)
                    .build();
            chatSessionMapper.insert(newSession);
            session = newSession;
        } else {
            // 更新最后活跃时间
            chatSessionMapper.updateTime(chatMemory.getSessionId());
        }
        chatMemoryMapper.insert(chatMemory);
        return Pair.of(session, chatMemory);
    }

    @Transactional
    public void deleteSession(String sessionId) {
        int rows = chatSessionMapper.deleteBySessionId(sessionId);
        if (rows > 0) {
            chatMemoryMapper.deleteBySessionId(sessionId);
        } else {
            log.warn("删除会话不存在");
        }
        // 没有删除会话中的图片等其他存储信息
    }

    /**
     * 模式一：流式对话 (Stream)
     * 使用 SSE 返回
     */
    @Transactional
    public SseEmitter streamChat(ChatRequestDTO request) {
        SseEmitter emitter = new SseEmitter(180000L); // 3分钟超时
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        buildRequest(request);
        LLMContext context = buildContext(request, userId);

        // 历史记录
        List<ChatMemory> oldHistory = chatMemoryMapper.findBySessionId(request.getSessionId());
        Pair<String, String> pair = this.convert(request);
        Pair<ChatSession, ChatMemory> memory = saveMemory(ChatMemory.builder()
                .sessionId(request.getSessionId())
                .userId(userId)
                .role("user")
                .type(pair.getKey())
                .content(pair.getValue())
                .build(), context);
        List<ChatMemory> history = new ArrayList<>(oldHistory);
        history.add(memory.getValue());

        // 2. 异步处理流式响应
        taskExecutor.execute(() -> {
            try {
                LLM llm = LLM.create(context);
                Prompt prompt = Prompt.create();

                prompt.addHistory(history, fileStorageService, objectMapper);

                StringBuilder fullResponse = new StringBuilder();

                try (Stream<String> stream = llm.stream(prompt, context)) {
                    stream.forEach(token -> {
                        try {
                            if (token != null) {
                                fullResponse.append(token);
                                // json返回
                                emitter.send(SseEmitter.event().data(Collections.singletonMap("c", token)));
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("SSE Send Error", e);
                        }
                    });
                    // 3. 流结束后，存入 AI 回复
                    saveMemory(ChatMemory.builder()
                            .sessionId(request.getSessionId())
                            .userId(userId)
                            .role("assistant")
                            .content(fullResponse.toString())
                            .type("text")
                            .build(), context);
                    emitter.complete();
                }
            } catch (Exception e) {
                log.error("Stream Chat Error", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data("Error: " + e.getMessage()));
                } catch (IOException ex) { /* ignore */ }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 模式二：Agent 对话 (Non-Stream)
     * 同步等待结果返回
     */
    @Transactional
    public ChatCompletionMessage agentChat(ChatRequestDTO request) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        buildRequest(request);
        LLMContext context = buildContext(request, userId);

        // 1. 存入用户消息
        Pair<String, String> pair = this.convert(request);
        saveMemory(ChatMemory.builder()
                .sessionId(request.getSessionId())
                .userId(userId)
                .role("user")
                .type(pair.getKey())
                .content(pair.getValue())
                .build(), context);

        try {
            // 2. 调用 Agent 执行 (Agent 内部会自行查询数据库获取历史记录)
            // 注意：Agent.invoke 可能会比较耗时
            ChatCompletionMessage message = agent.invoke(context);

            String value = objectMapper.writeValueAsString(message);
            saveMemory(ChatMemory.builder()
                    .sessionId(request.getSessionId())
                    .userId(userId)
                    .role("assistant")
                    .content(value)
                    .type("tool")
                    .build(), context);
            return message;
        } catch (Exception e) {
            log.error("Agent Chat Error", e);
            throw new RuntimeException("Agent processing failed: " + e.getMessage());
        }
    }

    @Transactional
    public MindGenResponseDTO mindChat(ChatRequestDTO request) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        buildRequest(request);
        LLMContext context = buildContext(request, userId);

        // 1. 保存用户提问至 ChatMemory
        Pair<String, String> pair = this.convert(request);
        saveMemory(ChatMemory.builder()
                .sessionId(request.getSessionId())
                .userId(userId)
                .role("user")
                .type(pair.getKey()) // text 或 file
                .content(pair.getValue())
                .build(), context);

        // 2. 加载历史记录并调用 LLM
        LLM llm = LLM.create(context);
        Prompt prompt = Prompt.create();
        List<ChatMemory> history = chatMemoryMapper.findBySessionId(request.getSessionId());
        prompt.addHistory(history, fileStorageService, objectMapper);

        String responseText = llm.noStream(prompt, context);

        // 3. 后处理：提取 JSON
        MindGenResponseDTO dto = parseMindResponse(responseText);

        // 4. 将 AI 结构化结果也保存到历史记录中，供前端渲染
        try {
            String aiContent = objectMapper.writeValueAsString(dto);
            saveMemory(ChatMemory.builder()
                    .sessionId(request.getSessionId())
                    .userId(userId)
                    .role("assistant")
                    .content(aiContent)
                    .type("mind_result") // 前端识别此类型并渲染出【一键导入】按钮
                    .build(), context);
        } catch (JsonProcessingException e) {
            log.error("AI 历史记录序列化失败", e);
        }

        return dto;
    }

    /**
     * 1. 异步后台处理：上传文件后触发，生成摘要并存入数据库
     */
    public void asyncAIFileSummary(AIFileSummaryDTO request) {
        for (String path : request.getPaths()) {
            String actualPath = wareService.buildActualPath(path);
            Node node = nodeMapper.selectNodeByPath(actualPath);
            taskExecutor.execute(() -> {
                try {
                    log.info("开始异步提取文件摘要: {}", path);
                    String summary = generateFileSummary(path);
                    nodeMetadataMapper.insert(NodeMetadata.builder()
                            .nodeId(node.getId())
                            .aiSummary(summary)
                            .build());
                    log.info("文件摘要提取完成: {}", path);
                } catch (Exception e) {
                    log.error("异步提取文件摘要失败: {}", path, e);
                }
            });
        }
    }

    /**
     * 通用内部方法：调用 LLM 获取文件摘要文本
     */
    private String generateFileSummary(String path) throws IOException {
        FileObjectResult fileObject = fileStorageService.getFileObject(path);
        FileItem fileItem = FileItem.builder()
                .fileName(fileObject.getName())
                .mimeTypeName(fileObject.getMimeTypeName())
                .fileSize(fileObject.getSize())
                .path(path)
                .stream(fileObject.getContent())
                .build();

        ContentPartMessage cpm = ContentPartMessage.builder()
                .text("请提取该文件的主要内容描述。")
                .files(List.of(fileItem))
                .build();

        // 构建专属上下文
        LLMGlobalConfig.SceneConfig sceneConfig = llmGlobalConfig.getScenes().getOrDefault("file-summary",
                llmGlobalConfig.getScenes().get("default"));

        LLMContext context = LLMContext.builder()
                .scene("file-summary")
                .llmConfig(sceneConfig)
                .systemPrompt(RAG.FILE_SUMMARY_SYSTEM_PROMPT)
                .build();

        Prompt prompt = Prompt.create().addContentPartMessageMessageUser(cpm);
        LLM llm = LLM.create(context);
        return llm.noStream(prompt, context);
    }

    private MindGenResponseDTO parseMindResponse(String responseText) {
        MindGenResponseDTO dto = new MindGenResponseDTO();
        try {
            String jsonContent = responseText.trim();
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("```(?:json)?\\s*(\\{.*?\\})\\s*```", java.util.regex.Pattern.DOTALL).matcher(jsonContent);
            if (matcher.find()) {
                jsonContent = matcher.group(1);
            } else {
                int start = jsonContent.indexOf("{");
                int end = jsonContent.lastIndexOf("}");
                if (start != -1 && end != -1) {
                    jsonContent = jsonContent.substring(start, end + 1);
                }
            }
            com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(jsonContent);
            dto.setThoughts(rootNode.path("thoughts").asText("好的，为你生成了积木代码。"));
            dto.setBlocklyXml(rootNode.path("blocklyXml").asText("<xml xmlns=\"https://developers.google.com/blockly/xml\"></xml>"));
        } catch (Exception e) {
            dto.setThoughts("AI 分析完成，但数据格式解析异常。原始返回：" + responseText);
            dto.setBlocklyXml("<xml xmlns=\"https://developers.google.com/blockly/xml\"></xml>");
        }
        return dto;
    }

    // 将request转换为字符串的内容，第一个结果指示是否用户输入类型
    private Pair<String, String> convert(ChatRequestDTO request) {
        String type = "text";
        if (request.getContentPartMessage() != null) {
            type = "file";
            try {
                String value = objectMapper.writeValueAsString(request.getContentPartMessage());
                return Pair.of(type, value);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return Pair.of(type, request.getMessage());
    }
}