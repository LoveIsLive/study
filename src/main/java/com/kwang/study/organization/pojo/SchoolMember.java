package com.kwang.study.organization.pojo;

import com.kwang.study.auth.pojo.User;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SchoolMember {
    private Long id;
    private Long schoolId;
    private Long userId;
    private String role;
    private LocalDateTime joinTime;

    private User user;
    private School school;
}