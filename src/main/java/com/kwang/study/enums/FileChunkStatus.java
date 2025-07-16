package com.kwang.study.enums;

import lombok.Getter;

@Getter
public enum FileChunkStatus {
    UPLOADED(0, "在上传"),
    MERGED(1, "已合并"),
    ;

    private final Integer code;
    private final String desc;

    FileChunkStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
