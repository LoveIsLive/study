package com.kwang.study.mathvision.engine;

import com.kwang.study.mathvision.enums.StageEnum;
import com.kwang.study.mathvision.pojo.MathVisionTask;
import com.kwang.study.mathvision.workflow.model.StageGenerationMode;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MathVisionStageExecutionContext {

    private MathVisionTask task;
    private StageEnum stage;
    private Runnable cancellationCheck;
    @Builder.Default
    private StageGenerationMode generationMode = StageGenerationMode.INITIAL_GENERATION;
    private Integer baseStageVersion;
    private String instruction;
    private String existingArtifactJson;
    private boolean stopAfterStage;

    public void checkCanceled() {
        if (cancellationCheck != null) {
            cancellationCheck.run();
        }
    }

    public boolean isUserRevision() {
        return StageGenerationMode.USER_REVISION.equals(generationMode);
    }
}
