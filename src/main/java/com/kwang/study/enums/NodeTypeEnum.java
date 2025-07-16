package com.kwang.study.enums;

import lombok.Getter;

@Getter
public enum NodeTypeEnum {
    DIR(0, "目录"),
    FILE(1, "文件"),

    CHUNK_INTERM(100, "分块上传中间态"),

    ;
    private final Integer code;
    private final String name;

    NodeTypeEnum(Integer code, String name) {
        this.code = code;
        this.name = name;
    }
}
