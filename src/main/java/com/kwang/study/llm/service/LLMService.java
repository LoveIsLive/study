package com.kwang.study.llm.service;

import cn.hutool.core.lang.Pair;
import cn.hutool.core.lang.Tuple;
import cn.hutool.core.lang.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.llm.config.LLMGlobalConfig;
import com.kwang.study.llm.core.*;
import com.kwang.study.llm.dto.request.ChatRequestDTO;
import com.kwang.study.llm.mapper.ChatMemoryMapper;
import com.kwang.study.llm.mapper.ChatSessionMapper;
import com.kwang.study.llm.pojo.ChatMemory;
import com.kwang.study.llm.pojo.ChatSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.util.function.Tuple2;

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
     * 构建上下文辅助方法
     */
    private LLMContext buildContext(ChatRequestDTO request, Long userId) {
        LLMGlobalConfig.SceneConfig sceneConfig = llmGlobalConfig.getScenes().getOrDefault(request.getScene(),
                llmGlobalConfig.getScenes().get("default"));

        // TODO: 处理RAG

        return LLMContext.builder()
                .userId(userId)
                .sessionId(request.getSessionId())
                .scene(request.getScene())
                .sceneParams(request.getSceneParams())
                .llmConfig(sceneConfig)
                .build();
    }

    // 保存历史记录，如果会话还没创建，先创建会话记录
    @Transactional
    public Pair<ChatSession, ChatMemory> saveMemory(String sessionId, Long userId, String role, String content) {
        ChatSession session = chatSessionMapper.findBySessionId(sessionId);
        if (session == null) {
            // 只有当这是第一条消息（通常是User发的）才创建，或者系统恢复时
            String title;
            if ("user".equals(role)) {
                title = content.length() > 20 ? content.substring(0, 20) + "..." : content;
            } else {
                title = "新会话"; // 防止第一条是 AI 发的（极少情况）
            }

            ChatSession newSession = ChatSession.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .title(title)
                    .build();
            chatSessionMapper.insert(newSession);
            session = newSession;
        } else {
            // 更新最后活跃时间
            chatSessionMapper.updateTime(sessionId);
        }
        ChatMemory memory = ChatMemory.builder()
                .sessionId(sessionId)
                .userId(userId)
                .role(role)
                .content(content)
                .build();
        chatMemoryMapper.insert(memory);
        return Pair.of(session, memory);
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

        LLMContext context = buildContext(request, userId);

        // 历史记录
        List<ChatMemory> oldHistory = chatMemoryMapper.findBySessionId(request.getSessionId());

        Pair<ChatSession, ChatMemory> memory = saveMemory(request.getSessionId(), userId, "user", request.getMessage());
        List<ChatMemory> history = new ArrayList<>(oldHistory);
        history.add(memory.getValue());

        // 2. 异步处理流式响应
        taskExecutor.execute(() -> {
            try {
                LLM llm = LLM.create(context);
                Prompt prompt = Prompt.create();

                prompt.addHistory(history);

                StringBuilder fullResponse = new StringBuilder();

                try (Stream<String> stream = llm.stream(prompt)) {
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
                    // 3. 流结束后，存入 AI 回复（以Tool统一形式存入）
                    Tools.ReplayTool replayTool = new Tools.ReplayTool();
                    replayTool.message = fullResponse.toString();
                    String value = objectMapper.writeValueAsString(List.of(replayTool));
                    saveMemory(request.getSessionId(), userId, "assistant", value);
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
    public List<Tools.Tool> agentChat(ChatRequestDTO request) {
        Long userId = AuthenticationUserUtil.getCurrentUserId();

        // 1. 存入用户消息
        saveMemory(request.getSessionId(), userId, "user", request.getMessage());

        LLMContext context = buildContext(request, userId);

        try {
            // 2. 调用 Agent 执行 (Agent 内部会自行查询数据库获取历史记录)
            // 注意：Agent.invoke 可能会比较耗时
            List<Tools.Tool> tools = agent.invoke(context);

            String value = objectMapper.writeValueAsString(tools);
            saveMemory(request.getSessionId(), userId, "assistant", value);

            return tools;
        } catch (Exception e) {
            log.error("Agent Chat Error", e);
            throw new RuntimeException("Agent processing failed: " + e.getMessage());
        }
    }
}