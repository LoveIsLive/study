package com.kwang.study.llm.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileNameAndPath {
    private String fileName;
    private String filePath;
}
