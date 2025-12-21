package com.kwang.study.llm.pojo;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class ChatMemory {
    private Long id;
    private String sessionId;
    private Long userId;
    private String role; // "user", "assistant"
    private String type;
    /**
     * 如果 type=text，存纯文本
     * 如果 type=file，存 JSON ChatContentMessage
     */
    private String content;
    private LocalDateTime createdAt;
}