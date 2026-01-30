package com.kwang.study.homework.dto.request;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

@Data
public class SubmissionCreateDTO {

    @NotNull(message = "Homework ID cannot be null")
    private Long homeworkId;

    private String content;

    private Map<String, Object> answerData; // 学生的答案 JSON

    private List<String> attachmentUploadIds; // 大附件的uploadId, 小附件不使用此
}
