package com.kwang.study.llm.dto.request;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import java.util.Map;

@Data
public class ChatRequestDTO {
    @NotBlank(message = "Session ID cannot be empty")
    private String sessionId;

    @NotBlank(message = "Message cannot be empty")
    private String message;

    // 上下文感知参数
    private String scene;
    private Map<String, Object> sceneParams;
}