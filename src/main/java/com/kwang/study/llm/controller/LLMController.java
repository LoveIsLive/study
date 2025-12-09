package com.kwang.study.llm.controller;

import com.kwang.study.common.R;
import com.kwang.study.llm.core.Tools;
import com.kwang.study.llm.dto.request.ChatRequestDTO;
import com.kwang.study.llm.mapper.ChatMemoryMapper;
import com.kwang.study.llm.pojo.ChatMemory;
import com.kwang.study.llm.pojo.ChatSession;
import com.kwang.study.llm.service.LLMService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.validation.Valid;
import java.util.List;

import static com.kwang.study.constant.ApiPrefixConstant.API_BASE_PREFIX;

@RestController
@RequestMapping(API_BASE_PREFIX + "/llm")
@RequiredArgsConstructor
public class LLMController {

    private final LLMService llmService;
    private final ChatMemoryMapper chatMemoryMapper;

    /**
     * 1. 获取新的 Session ID
     */
    @GetMapping("/session/new")
    public ResponseEntity<R<String>> createSession() {
        return ResponseEntity.ok(R.success(llmService.createSessionId()));
    }

    /**
     * 2. 流式对话接口 (Stream Mode)
     * 适用于快速问答、普通聊天
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody ChatRequestDTO request) throws Exception {
        return llmService.streamChat(request);
    }

    /**
     * 3. Agent 对话接口 (Agent Mode)
     * 适用于需要工具调用、复杂任务规划的场景
     */
    @PostMapping("/chat/agent")
    public ResponseEntity<R<List<Tools.Tool>>> agentChat(@Valid @RequestBody ChatRequestDTO request) throws Exception {
        List<Tools.Tool> result = llmService.agentChat(request);
        return ResponseEntity.ok(R.success(result));
    }

    /**
     * 获取历史记录
     */
    @GetMapping("/history/{sessionId}")
    public ResponseEntity<R<List<ChatMemory>>> getHistory(@PathVariable String sessionId) {
        return ResponseEntity.ok(R.success(chatMemoryMapper.findBySessionId(sessionId)));
    }

    @GetMapping("/sessions")
    public ResponseEntity<R<List<ChatSession>>> getSessions() {
        return ResponseEntity.ok(R.success(llmService.getUserSessions()));
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<R<Void>> deleteSession(@PathVariable String sessionId) {
        llmService.deleteSession(sessionId);
        return ResponseEntity.ok(R.success(null));
    }
}