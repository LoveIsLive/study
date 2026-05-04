package com.kwang.study.llm.core;

import com.kwang.study.llm.config.LLMGlobalConfig;
import com.kwang.study.llm.dto.request.ChatRequestDTO;
import lombok.Builder;
import lombok.Data;

import javax.validation.constraints.Null;
import java.util.Map;

@Data
@Builder
public class LLMContext {
    @Null
    private Long userId;
    @Null
    private String sessionId;

    @Null
    private String scene;
    @Null
    private Map<String, Object> sceneParams;
    @Null
    private ChatRequestDTO request;

    // 包含当前场景的大模型配置
    private LLMGlobalConfig.SceneConfig llmConfig;
    // 填充后的systemPrompt
    private String systemPrompt;
}