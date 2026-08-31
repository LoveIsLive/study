package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.model.StageGenerationMode;
import lombok.Builder;
import lombok.Data;

import java.util.function.Consumer;

@Data
@Builder
public class MathVisionStageExecutionContext {

    private MathVisionTask task;
    private StageEnum stage;
    private Runnable cancellationCheck;
    private Consumer<Runnable> cancellationHookRegistrar;
    private Consumer<Runnable> cancellationHookClearer;
    @Builder.Default
    private StageGenerationMode generationMode = StageGenerationMode.INITIAL_GENERATION;
    private Integer baseStageVersion;
    private String instruction;
    private String existingArtifactJson;
    private String existingStageResultJson;
    private boolean qualityReviewRequested;
    private boolean stopAfterStage;

    public void checkCanceled() {
        if (cancellationCheck != null) {
            cancellationCheck.run();
        }
    }

    public void registerCancellationHook(Runnable hook) {
        if (cancellationHookRegistrar != null && hook != null) {
            cancellationHookRegistrar.accept(hook);
        }
    }

    public void clearCancellationHook(Runnable hook) {
        if (cancellationHookClearer != null && hook != null) {
            cancellationHookClearer.accept(hook);
        }
    }

    public boolean isUserRevision() {
        return StageGenerationMode.USER_REVISION.equals(generationMode);
    }
}
