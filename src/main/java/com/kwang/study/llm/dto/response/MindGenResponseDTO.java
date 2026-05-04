package com.kwang.study.llm.dto.response;
import lombok.Data;

@Data
public class MindGenResponseDTO {
    private String thoughts;
    private String blocklyXml;
}