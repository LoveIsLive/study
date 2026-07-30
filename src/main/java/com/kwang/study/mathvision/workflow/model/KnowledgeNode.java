package com.kwang.study.mathvision.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({
        "id",
        "step",
        "reason",
        "node_type",
        "min_depth",
        "equations",
        "definitions",
        "interpretation",
        "examples"
})
public class KnowledgeNode {

    public static final String NODE_TYPE_CONCEPT = "concept";
    public static final String NODE_TYPE_PROBLEM = "problem";
    public static final String NODE_TYPE_OBSERVATION = "observation";
    public static final String NODE_TYPE_CONSTRUCTION = "construction";
    public static final String NODE_TYPE_DERIVATION = "derivation";
    public static final String NODE_TYPE_CONCLUSION = "conclusion";

    @JsonProperty("id")
    private String id;

    @JsonProperty("step")
    private String step;

    @JsonProperty("reason")
    private String reason;

    @JsonProperty("node_type")
    private String nodeType = NODE_TYPE_CONCEPT;

    @JsonProperty("min_depth")
    private int minDepth = -1;

    @JsonProperty("equations")
    private List<String> equations;

    @JsonProperty("definitions")
    private Map<String, String> definitions;

    @JsonProperty("interpretation")
    private String interpretation;

    @JsonProperty("examples")
    private List<String> examples;

    public KnowledgeNode() {
    }

    public KnowledgeNode(String id, String step, int minDepth) {
        this.id = id;
        this.step = step;
        this.minDepth = minDepth;
    }

    public String getNodeType() {
        return nodeType == null || nodeType.isBlank() ? NODE_TYPE_CONCEPT : nodeType;
    }

    public boolean isEnriched() {
        return equations != null && definitions != null;
    }
}
