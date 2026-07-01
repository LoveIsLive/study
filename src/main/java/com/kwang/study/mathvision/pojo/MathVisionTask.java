package com.kwang.study.mathvision.pojo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MathVision 生成任务主表 (与 chat_sessions 1:1)。
 * 对应表 mathvision_tasks。
 */
@Data
@Builder
public class MathVisionTask {
    private Long id;
    /** 对应 chat_sessions.session_id */
    private String sessionId;
    private Long userId;
    /** 文本输入; 纯图片时可为空或存补充说明 */
    private String inputText;
    /** text/markdown/image/mixed */
    private String inputSourceType;
    /** 资产信息 (JSON 文本): fileName/filePath/mimeTypeName/fileSize/source */
    private String inputAssetsJson;
    /** manual/auto */
    private String mode;
    /** manim/geogebra */
    private String outputTarget;
    /** created/queued/running/waiting_confirm/failed/completed/canceled */
    private String status;
    /** problem_normalization/reasoning_graph/visual_storyboard/code_generation/render_result/completed */
    private String currentStage;
    /** 失败发生的阶段 */
    private String failedStage;
    /** 错误类型枚举 */
    private String errorType;
    /** 失败原因摘要 */
    private String errorMessage;
    /** 对应 llm_model_configs.id */
    private Long selectedModelConfigId;
    /** 冗余, 便于展示/校验 */
    private String providerCode;
    /** 本次实际使用的模型名称 */
    private String modelName;
    /** 当前激活/工作版本, 指向 mathvision_versions.version */
    private Integer currentVersion;
    /** 手动模式最近确认到的阶段 */
    private String lastConfirmedStage;
    /** 自动修复累计次数 (限流) */
    private Integer autoFixCount;
    /** 当前版本最终产物路径 */
    private String finalArtifactPath;
    /** mp4/html */
    private String finalArtifactType;
    /** 创建幂等键 */
    private String requestId;
    /** 软删除标记 */
    private Boolean deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
