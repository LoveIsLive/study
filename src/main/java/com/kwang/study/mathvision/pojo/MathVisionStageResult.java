package com.kwang.study.mathvision.pojo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 阶段校验 / 执行结果 (同 mathvision_artifacts 的 grain; 结果列合一为 resultJson)。
 * 对应表 mathvision_stage_results。
 *
 * resultJson 形状随 stage:
 *   visual_storyboard -> { "storyboardValidation": {...} }
 *   code_generation   -> { "codeEvaluation": {...}, "codeFixTrace": [...],
 *                          "geogebraReviewedText": "...", "geogebraValidation": {...} }
 *   render_result     -> { "renderResult": {...}, "sceneEvaluation": {...} }
 */
@Data
@Builder
public class MathVisionStageResult {
    private Long id;
    private Long taskId;
    /** 对应 mathvision_artifacts.id (同 stage 同 version) */
    private Long artifactId;
    /** 冗余, 便于按会话查询 */
    private String sessionId;
    /** 冗余, 便于权限校验 */
    private Long userId;
    /** 同 mathvision_artifacts.stage */
    private String stage;
    /** 阶段独立版本号, 与 artifacts 一致 */
    private Integer version;
    /** 统一结果列 (JSON 文本), 形状随 stage */
    private String resultJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
