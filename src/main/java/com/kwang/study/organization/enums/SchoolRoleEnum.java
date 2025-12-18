package com.kwang.study.organization.enums;

import lombok.Getter;

@Getter
public enum SchoolRoleEnum {
    PRINCIPAL("ROLE_PRINCIPAL"),

    ;
    private final String role;

    SchoolRoleEnum(String role) {
        this.role = role;
    }
}
