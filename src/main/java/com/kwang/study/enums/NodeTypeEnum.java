package com.kwang.study.enums;

import lombok.Getter;

@Getter
public enum NodeTypeEnum {
    DIR(0, "目录"),
    FILE(1, "文件"),

    ;
    private final int code;
    private final String name;

    NodeTypeEnum(int code, String name) {
        this.code = code;
        this.name = name;
    }
}
