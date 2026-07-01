package com.kwang.study.mathvision.pojo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务版本组合表 (UX 的 V1/V2/V3)。
 * 对应表 mathvision_versions。
 *
 * 每行 = 对五阶段各选一个"阶段版本号"的指针组合;
 * 上游未改的阶段, 多个任务版本填同一个阶段版本号, 底层产物只存一份。
 */
@Data
@Builder
public class MathVisionVersion {
    private Long id;
    private Long taskId;
    /** 任务级版本号 (UX 的 V1/V2/V3) */
    private Integer version;
    /** 来源任务版本 */
    private Integer baseVersion;
    /** problem_normalization 选用的阶段版本号 */
    private Integer pnVersion;
    /** reasoning_graph 阶段版本号 */
    private Integer rgVersion;
    /** visual_storyboard 阶段版本号 */
    private Integer vsVersion;
    /** code_generation 阶段版本号 */
    private Integer cgVersion;
    /** render_result 阶段版本号 (NULL=尚未生成) */
    private Integer rrVersion;
    /** 本版本从哪个阶段分叉 (决定下游清空范围) */
    private String branchStage;
    /** initial_generation/user_revision/manual_edit/regenerate/auto_fix/retry */
    private String changeSource;
    /** 版本变更摘要 */
    private String changeSummary;
    /** 整次 workflow 执行摘要 (运行级, JSON 文本) */
    private String workflowSummaryJson;
    /** 是否当前版本 */
    private Boolean isCurrent;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
