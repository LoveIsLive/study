package com.kwang.study.enums;

import lombok.Getter;

@Getter
public enum PermissionsEnum {
    ALL("rwxrwxrwx", "所有权限"),

    ;

    private final String code;
    private final String desc;

    PermissionsEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
