package com.kwang.study.homework.pojo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * @author kwang
 * @date 2025/08/29
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper=true)
public class HomeworkSubmissionDetail extends HomeworkSubmission {
    private static final long serialVersionUID = 4038440185030060752L;
    private String studentName;
}
