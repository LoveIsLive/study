package com.kwang.study.mathvision.workflow.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class SceneCodeEntry {

    @JsonProperty("scene_index")
    private int sceneIndex;

    @JsonProperty("scene_id")
    private String sceneId;

    @JsonProperty("scene_method_name")
    private String sceneMethodName;

    @JsonProperty("scene_code")
    private String sceneCode;

    @JsonProperty("validated")
    private boolean validated;

    public SceneCodeEntry() {
    }

    public SceneCodeEntry(int sceneIndex,
                          String sceneId,
                          String sceneMethodName,
                          String sceneCode,
                          boolean validated) {
        this.sceneIndex = sceneIndex;
        this.sceneId = sceneId;
        this.sceneMethodName = sceneMethodName;
        this.sceneCode = sceneCode;
        this.validated = validated;
    }
}
