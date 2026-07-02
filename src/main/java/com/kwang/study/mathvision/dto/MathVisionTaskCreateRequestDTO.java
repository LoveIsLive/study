package com.kwang.study.mathvision.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * 创建 MathVision 任务请求 (multipart 的 request 部分)。
 */
@Data
public class MathVisionTaskCreateRequestDTO {

    /** 会话 ID; 为空则后端自动创建 chat_sessions(purpose=mathvision)。 */
    private String sessionId;

    /** 用户输入文本。 */
    @NotBlank(message = "Message cannot be empty")
    private String message;

    /** 任务标题; 为空则由 message 截取。 */
    private String title;

    /** 输入来源类型: text/markdown/image/mixed。 */
    private String inputSourceType;

    /** 生成模式: manual/auto。 */
    @NotBlank(message = "Mode cannot be empty")
    private String mode;

    /** 输出目标: manim/geogebra。 */
    @NotBlank(message = "Output target cannot be empty")
    private String outputTarget;

    /** 模型厂家: openai/anthropic/google/moonshot/zhipu。 */
    @NotBlank(message = "Provider code cannot be empty")
    private String providerCode;

    /** 模型名称, 来自模型列表接口。 */
    @NotBlank(message = "Model name cannot be empty")
    private String modelName;

    /** 大文件上传完成后的文件引用。 */
    private List<FileNameAndPath> uploadFiles;

    /** 请求 ID, 用于幂等。 */
    private String requestId;

    /** 是否创建后立即启动 workflow。 */
    private Boolean autoStart;

    /** 大文件引用条目 {fileName, filePath}。 */
    @Data
    public static class FileNameAndPath {
        private String fileName;
        private String filePath;
    }
}
