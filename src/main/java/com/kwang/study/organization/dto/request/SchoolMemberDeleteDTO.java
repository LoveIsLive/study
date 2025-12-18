package com.kwang.study.organization.dto.request;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
public class SchoolMemberDeleteDTO {
    /**
     * 待删除的用户ID列表
     */
    @NotEmpty(message = "用户ID列表不能为空")
    private List<Long> userIds;
}