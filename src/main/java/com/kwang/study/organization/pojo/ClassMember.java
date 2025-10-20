package com.kwang.study.organization.pojo;

import com.kwang.study.auth.pojo.User;
import lombok.Data;

import java.time.LocalDateTime;

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
}
