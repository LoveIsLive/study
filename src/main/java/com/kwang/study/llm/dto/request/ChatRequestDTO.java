package com.kwang.study.llm.dto.request;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Data
public class ChatRequestDTO {
    @NotBlank(message = "Session ID cannot be empty")
    private String sessionId;

    @NotBlank(message = "Message cannot be empty")
    private String message;

    // 请求类型，stream / agent
    private String type;

    // 上下文感知参数
    private String scene;
    private Map<String, Object> sceneParams;

    /* 非前端传入部分 */

    // 当存在时，表示前端有附件上传
    private ContentPartMessage contentPartMessage;

    private String requestId;
}