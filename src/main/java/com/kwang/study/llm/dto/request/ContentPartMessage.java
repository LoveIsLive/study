package com.kwang.study.llm.dto.request;

import com.kwang.study.dto.FileItem;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ContentPartMessage {
    private String text;
    private List<FileItem> files;
}
