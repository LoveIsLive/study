package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.enums.StageEnum;

public interface MathVisionStageExecutor {

    StageEnum stage();

    MathVisionStageExecutionResult execute(MathVisionStageExecutionContext context);
}
