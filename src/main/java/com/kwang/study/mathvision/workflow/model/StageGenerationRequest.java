package com.kwang.study.mathvision.workflow.model;

import lombok.Builder;
import lombok.Data;

/**
 * Typed invocation contract shared by stage 1, 2, and 4 generation nodes.
 * The existing artifact is context for a full regeneration, not a patch target.
 */
@Data
@Builder
public class StageGenerationRequest<T> {

    @Builder.Default
    private StageGenerationMode mode = StageGenerationMode.INITIAL_GENERATION;

    private T existingArtifact;

    private String instruction;

    private Integer baseStageVersion;

    public static <T> StageGenerationRequest<T> initialGeneration() {
        return StageGenerationRequest.<T>builder()
                .mode(StageGenerationMode.INITIAL_GENERATION)
                .build();
    }

    public boolean isUserRevision() {
        return StageGenerationMode.USER_REVISION.equals(mode);
    }
}
