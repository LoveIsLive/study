package com.kwang.study.organization.pojo;

import com.kwang.study.auth.pojo.User;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ClassMember {
    private Long id;
    private Long classId;
    private Long userId;
    private String role;
    private LocalDateTime joinTime;

    // 关联的User对象
    private User user;
    // 关联的Classes对象
    private Classes classes;

    // 如果 role 是 ROLE_GUEST，这里存储该访客被授权访问的 courseId 列表
    private List<Long> allowedCourseIds;
}
