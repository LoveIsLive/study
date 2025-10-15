package com.kwang.study.auth.enums;

import lombok.Getter;

@Getter
public enum ClassesRoleEnum {
    TEACHER("ROLE_TEACHER"),
    STUDENT("ROLE_STUDENT"),

    ;

    private final String role;

    ClassesRoleEnum(String role) {
        this.role = role;
    }
}
