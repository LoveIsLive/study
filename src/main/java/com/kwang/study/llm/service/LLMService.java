package com.kwang.study.llm.service;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.lang.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.llm.config.LLMGlobalConfig;
import com.kwang.study.llm.core.*;
import com.kwang.study.llm.dto.request.ChatRequestDTO;
import com.kwang.study.llm.dto.request.ContentPartMessage;
import com.kwang.study.llm.mapper.ChatMemoryMapper;
import com.kwang.study.llm.mapper.ChatSessionMapper;
import com.kwang.study.llm.pojo.ChatMemory;
import com.kwang.study.llm.pojo.ChatSession;
import com.openai.models.chat.completions.ChatCompletionMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    /**
     * 生成新的会话ID
     */
    public String createSessionId() {
        return UUID.randomUUID().toString();
    }

    public List<ChatSession> getUserSessions() {
        Long userId = AuthenticationUserUtil.getCurrentUserId();
        return chatSessionMapper.findByUserId(userId);
    }

    /**
     * 构建request
     */
    private void buildRequest(ChatRequestDTO request) {
        request.setRequestId(UUID.randomUUID().toString());
    }

    /**
     * 构建上下文辅助方法
     */
    private LLMContext buildContext(ChatRequestDTO request, Long userId) {
        LLMGlobalConfig.SceneConfig sceneConfig = llmGlobalConfig.getScenes().getOrDefault(request.getScene(),
                llmGlobalConfig.getScenes().get("default"));

        // 存在附件时强制使用qwen3-vl-plus模型
        if (request.getContentPartMessage() != null) {
            sceneConfig.setModelName("qwen3-vl-plus");
            sceneConfig.setApiKey("sk-d0d552efe91d4512b74c9cfdb671c544");
            sceneConfig.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        }

        // TODO: 处理RAG

        return LLMContext.builder()
                .userId(userId)
                .sessionId(request.getSessionId())
                .scene(request.getScene())
                .sceneParams(request.getSceneParams())
                .llmConfig(sceneConfig)
                .request(request)
                .build();
    }

    /**
     * 保存历史记录，如果会话还没创建，先创建会话记录
     * request和response有且仅能有一个不为null
     */
    @Transactional
    public Pair<ChatSession, ChatMemory> saveMemory(ChatMemory chatMemory) {
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

            ChatSession newSession = ChatSession.builder()
                    .sessionId(chatMemory.getSessionId())
                    .userId(chatMemory.getUserId())
                    .title(title)
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
                .build());
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
                            .build());
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
                .build());

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
                    .build());
            return message;
        } catch (Exception e) {
            log.error("Agent Chat Error", e);
            throw new RuntimeException("Agent processing failed: " + e.getMessage());
        }
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