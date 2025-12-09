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
    private String content;
    private LocalDateTime createdAt;
}