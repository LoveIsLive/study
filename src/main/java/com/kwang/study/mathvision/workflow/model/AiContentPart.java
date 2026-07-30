package com.kwang.study.mathvision.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class AiContentPart {

    @JsonProperty("type")
    private String type;

    @JsonProperty("text")
    private String text;

    @JsonProperty("mime_type")
    private String mimeType;

    @JsonProperty("data_base64")
    private String dataBase64;

    public static AiContentPart text(String text) {
        AiContentPart part = new AiContentPart();
        part.setType("text");
        part.setText(text);
        return part;
    }

    public static AiContentPart image(String mimeType, String dataBase64) {
        AiContentPart part = new AiContentPart();
        part.setType("image");
        part.setMimeType(mimeType);
        part.setDataBase64(dataBase64);
        return part;
    }
}
