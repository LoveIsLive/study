package com.kwang.study.homework.pojo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author kwang
 * @date 2025/08/29
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HomeworkSubmissionDetail extends HomeworkSubmission {
    private String studentName;
}
