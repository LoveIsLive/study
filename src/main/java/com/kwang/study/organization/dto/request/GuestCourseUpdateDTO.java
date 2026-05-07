package com.kwang.study.organization.dto.request;

import lombok.Data;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 修改访客课程权限的请求体
 */
@Data
public class GuestCourseUpdateDTO {

    /**
     * 重新分配的课程ID列表 (全量覆盖)
     * 传空列表代表收回所有课程权限
     */
    @NotNull(message = "课程ID列表不能为Null")
    private List<Long> courseIds;
}