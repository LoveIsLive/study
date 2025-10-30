package com.kwang.study.discussion.dto.request;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;

@Data
public class PostUpdateDTO {
    @NotEmpty(message = "内容不能为空")
    @Size(max = 5000, message = "内容长度不能超过5000字符")
    private String content;
}