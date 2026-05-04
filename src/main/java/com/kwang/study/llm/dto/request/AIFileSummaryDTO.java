package com.kwang.study.llm.dto.request;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
public class AIFileSummaryDTO {
    @NotEmpty
    private List<String> paths;
}
