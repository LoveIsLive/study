package com.kwang.study.organization.dto.request;

import lombok.Data;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 删除班级成员的请求体
 */
@Data
public class ClassMemberDeleteDTO {
    /**
     * 用户ID列表
     */
    @NotEmpty(message = "待删除的用户ID列表不能为空")
    private List<Long> userIds;
}