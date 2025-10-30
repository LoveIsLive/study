package com.kwang.study.discussion.dto.request;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class PostCreateDTO {

    @NotNull(message = "所属对象ID不能为空")
    private Long ownerId;

    @NotEmpty(message = "所属对象类型不能为空")
    private String ownerType; // "homework" or "submission"

    private Long parentId; // 回复的帖子ID，可以为null

    @NotEmpty(message = "内容不能为空")
    @Size(max = 5000, message = "内容长度不能超过5000字符")
    private String content;

    // 由后端从认证信息中设置
    private Long userId;
}