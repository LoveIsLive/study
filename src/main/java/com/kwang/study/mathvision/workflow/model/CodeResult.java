package com.kwang.study.mathvision.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class CodeResult {

    @JsonProperty("generated_code")
    private String generatedCode;

    @JsonProperty("header_code")
    private String headerCode;

    @JsonProperty("scene_entries")
    private List<SceneCodeEntry> sceneEntries = new ArrayList<>();

    @JsonProperty("scene_name")
    private String sceneName;

    @JsonProperty("description")
    private String description;

    @JsonProperty("output_target")
    private String outputTarget = "manim";

    @JsonProperty("artifact_format")
    private String artifactFormat = "python";

    @JsonProperty("artifact_name")
    private String artifactName;

    @JsonProperty("target_concept")
    private String targetConcept = "";

    @JsonProperty("target_description")
    private String targetDescription = "";

    @JsonProperty("tool_calls")
    private int toolCalls;

    @JsonProperty("execution_time_seconds")
    private double executionTimeSeconds;

    public boolean hasCode() {
        return generatedCode != null && !generatedCode.isBlank();
    }

    public int codeLineCount() {
        return hasCode() ? generatedCode.split("\\R").length : 0;
    }

    @JsonIgnore
    public boolean isGeoGebraTarget() {
        return "geogebra".equalsIgnoreCase(outputTarget);
    }

    @JsonIgnore
    public boolean isManimTarget() {
        return !isGeoGebraTarget();
    }

    public void setSceneEntries(List<SceneCodeEntry> sceneEntries) {
        this.sceneEntries = sceneEntries != null ? sceneEntries : new ArrayList<>();
    }

    public void appendSceneEntry(SceneCodeEntry entry) {
        if (entry != null) {
            sceneEntries.add(entry);
            rebuildGeneratedCode();
        }
    }

    public void replaceSceneCode(int index, String newCode) {
        if (index >= 0 && index < sceneEntries.size()) {
            sceneEntries.get(index).setSceneCode(newCode);
            rebuildGeneratedCode();
        }
    }

    public void rebuildGeneratedCode() {
        if (headerCode == null || headerCode.isBlank()) {
            return;
        }
        StringBuilder sb = new StringBuilder(headerCode);
        for (SceneCodeEntry entry : sceneEntries) {
            if (entry != null && entry.getSceneCode() != null && !entry.getSceneCode().isBlank()) {
                sb.append("\n\n").append(entry.getSceneCode());
            }
        }
        generatedCode = sb.toString();
    }
}
