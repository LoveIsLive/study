package com.kwang.study.mathvision.workflow.model;

import lombok.Builder;
import lombok.Data;

/**
 * VisualDesignNode 的调用契约。
 * 原有工作流使用 INITIAL_GENERATION；用户自动编辑显式使用 USER_REVISION。
 */
@Data
@Builder
public class VisualDesignRequest {

    @Builder.Default
    private VisualDesignMode mode = VisualDesignMode.INITIAL_GENERATION;

    /** USER_REVISION 的不可变基线产物。 */
    private Narrative existingNarrative;

    /** 用户自然语言修改意见。 */
    private String instruction;

    /** 基线 storyboard 的独立阶段版本号，仅用于提示和调用方追踪。 */
    private Integer baseStageVersion;

    public static VisualDesignRequest initialGeneration() {
        return VisualDesignRequest.builder()
                .mode(VisualDesignMode.INITIAL_GENERATION)
                .build();
    }

    public boolean isUserRevision() {
        return VisualDesignMode.USER_REVISION.equals(mode);
    }
}
