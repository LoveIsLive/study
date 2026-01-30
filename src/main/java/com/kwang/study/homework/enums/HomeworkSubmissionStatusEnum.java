package com.kwang.study.homework.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum HomeworkSubmissionStatusEnum {
    SUBMITTED("已提交"),
    RETURNED("被退回"),
    HAVE_UPDATED("作业有更新"),
    RE_SUBMITTED("重新提交"),
    GRADED("已批改"),

    ;

    private String value;

}
