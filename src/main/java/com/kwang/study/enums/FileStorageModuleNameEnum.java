package com.kwang.study.enums;

import lombok.Getter;

@Getter
public enum FileStorageModuleNameEnum {
    WARE_NAME("/ware", "课程仓库的模块名"),
    HOMEWORK_NAME("/homework", "作业区的模块名"),
    LLMCHAT_NAME("/llm-chat", "llm-chat模块名"),
    COVERIMAGE_NAME("/coverimage", "封面图片"),
    MATHVISION_NAME("/mathvision", "教学动画生成模块")
    ;
    private final String moduleName;
    private final String desc;

    FileStorageModuleNameEnum(String moduleName, String desc) {
        this.moduleName = moduleName;
        this.desc = desc;
    }
}
