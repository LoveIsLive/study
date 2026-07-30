package com.kwang.study.mathvision.pojo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 阶段核心产物 (按 task_id+stage 独立版本; 产物列合一为 artifactJson)。
 * 对应表 mathvision_artifacts。
 *
 * artifactJson 形状随 stage:
 *   problem_normalization -> ProblemBundle
 *   reasoning_graph       -> KnowledgeGraph (dag_graph)
 *   visual_storyboard     -> Narrative
 *   code_generation       -> { "format":"python|geogebra_commands", "text":"..." }
 *   render_result         -> { "artifactPath":"...", "artifactType":"mp4|html" }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MathVisionArtifact {
    private Long id;
    private Long taskId;
    /** 冗余, 便于按会话查询 */
    private String sessionId;
    /** 冗余, 便于权限校验 */
    private Long userId;
    /** problem_normalization/reasoning_graph/visual_storyboard/code_generation/render_result */
    private String stage;
    /** 该阶段独立版本号 (在 task_id+stage 内自增) */
    private Integer version;
    /** 本阶段来源版本 */
    private Integer baseVersion;
    /** 统一产物列 (JSON 文本), 形状随 stage */
    private String artifactJson;
    /** initial_generation/user_revision/manual_edit/regenerate/auto_fix/retry */
    private String changeSource;
    /** 相对上一版本的变更摘要 */
    private String changeSummary;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
