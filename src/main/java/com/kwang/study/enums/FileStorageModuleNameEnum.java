package com.kwang.study.enums;

import lombok.Getter;

@Getter
public enum FileStorageModuleNameEnum {
    WARE_NAME("/ware", "课程仓库的模块名"),
    HOMEWORK_NAME("/homework", "作业区的模块名")

    ;
    private final String moduleName;
    private final String desc;

    FileStorageModuleNameEnum(String moduleName, String desc) {
        this.moduleName = moduleName;
        this.desc = desc;
    }
}
