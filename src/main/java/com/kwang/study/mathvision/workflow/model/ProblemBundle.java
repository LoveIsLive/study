package com.kwang.study.mathvision.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ProblemBundle {

    @JsonProperty("id")
    private String id;

    @JsonProperty("title")
    private String title;

    @JsonProperty("input_mode")
    private String inputMode;

    @JsonProperty("output_target")
    private String outputTarget;

    @JsonProperty("scene_mode")
    private String sceneMode;

    @JsonProperty("source")
    private ProblemSource source;

    @JsonProperty("statement")
    private String statement;

    @JsonProperty("diagram")
    private ProblemDiagram diagram;

    public boolean hasDiagram() {
        return diagram != null && diagram.isPresent() && diagram.hasDescriptionPayload();
    }
}
