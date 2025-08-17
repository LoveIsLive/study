package com.kwang.study.fs.enums;

import lombok.Getter;

@Getter
public enum FileChunkStatus {
    INIT(0, "初态-分片刚上传"),
    MERGING(1, "合并中"),
    MERGE_SUCCESS(2, "合并成功"),
    MERGE_FAIL(3, "合并失败")
    ;

    private final Integer code;
    private final String desc;

    FileChunkStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
