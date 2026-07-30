package com.kwang.study.mathvision.workflow.model;

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

    @JsonProperty("generatedCode")
    private String generatedCode;

    private String headerCode;

    private List<SceneCodeEntry> sceneEntries = new ArrayList<>();

    @JsonProperty("sceneName")
    private String sceneName;

    @JsonProperty("description")
    private String description;

    @JsonProperty("outputTarget")
    private String outputTarget = "manim";

    @JsonProperty("artifactFormat")
    private String artifactFormat = "python";

    @JsonProperty("artifactName")
    private String artifactName;

    private String targetConcept = "";

    private String targetDescription = "";

    @JsonProperty("toolCalls")
    private int toolCalls;

    @JsonProperty("executionTimeSeconds")
    private double executionTimeSeconds;

    public boolean hasCode() {
        return generatedCode != null && !generatedCode.isBlank();
    }

    public int codeLineCount() {
        return hasCode() ? generatedCode.split("\\R").length : 0;
    }

    public boolean isGeoGebraTarget() {
        return "geogebra".equalsIgnoreCase(outputTarget);
    }

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
