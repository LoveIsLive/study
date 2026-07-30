package com.kwang.study.mathvision.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ProblemDiagram {

    @JsonProperty("present")
    private boolean present;

    @JsonProperty("source_observed")
    private boolean sourceObserved;

    @JsonProperty("diagram_description")
    private JsonNode diagramDescription;

    @JsonProperty("coordinate_model")
    private JsonNode coordinateModel;

    @JsonProperty("unknowns")
    private List<JsonNode> unknowns = new ArrayList<>();

    @JsonProperty("ambiguities")
    private List<JsonNode> ambiguities = new ArrayList<>();

    @JsonProperty("normalization_notes")
    private List<String> normalizationNotes = new ArrayList<>();

    public boolean hasDescriptionPayload() {
        return isMeaningful(diagramDescription)
                || isMeaningful(coordinateModel)
                || (unknowns != null && !unknowns.isEmpty())
                || (ambiguities != null && !ambiguities.isEmpty())
                || (normalizationNotes != null && !normalizationNotes.isEmpty());
    }

    private boolean isMeaningful(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isContainerNode()) {
            return node.size() > 0;
        }
        return !node.asText("").isBlank();
    }
}
