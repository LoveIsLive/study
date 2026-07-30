package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.enums.StageEnum;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class MathVisionStageExecutorRegistry {

    private final Map<StageEnum, MathVisionStageExecutor> executors = new EnumMap<>(StageEnum.class);

    public MathVisionStageExecutorRegistry(List<MathVisionStageExecutor> stageExecutors) {
        for (MathVisionStageExecutor executor : stageExecutors) {
            executors.put(executor.stage(), executor);
        }
    }

    public Optional<MathVisionStageExecutor> find(StageEnum stage) {
        return Optional.ofNullable(executors.get(stage));
    }
}
