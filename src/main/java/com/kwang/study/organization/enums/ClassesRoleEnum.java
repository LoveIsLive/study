package com.kwang.study.organization.enums;

import lombok.Getter;

@Getter
public enum ClassesRoleEnum {
    TEACHER("ROLE_TEACHER"),
    STUDENT("ROLE_STUDENT"),
    GUEST("ROLE_GUEST"),
    ;

    private final String role;

    ClassesRoleEnum(String role) {
        this.role = role;
    }
}
