package com.kwang.study.mathvision.enums;

/**
 * 用户可见阶段。value 为对外阶段编码, 与前端 / DB 一致。
 */
public enum StageEnum {

    PROBLEM_NORMALIZATION("problem_normalization"),
    REASONING_GRAPH("reasoning_graph"),
    VISUAL_STORYBOARD("visual_storyboard"),
    CODE_GENERATION("code_generation"),
    RENDER_RESULT("render_result"),
    COMPLETED("completed");

    private final String code;

    StageEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static StageEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (StageEnum s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        return null;
    }
}
