package com.kwang.study.llm.core;

import com.kwang.study.llm.config.LLMGlobalConfig;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class LLMContext {
    private Long userId;
    private String sessionId;

    private String scene;
    private Map<String, Object> sceneParams;

    // 包含当前场景的大模型配置
    private LLMGlobalConfig.SceneConfig llmConfig;
}