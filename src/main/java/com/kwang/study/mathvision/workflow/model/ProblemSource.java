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
public class ProblemSource {

    @JsonProperty("source_type")
    private String sourceType;

    @JsonProperty("raw_text")
    private String rawText;

    @JsonProperty("assets")
    private List<SourceAsset> assets = new ArrayList<>();
}
