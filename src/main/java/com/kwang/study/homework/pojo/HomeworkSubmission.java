package com.kwang.study.homework.pojo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class HomeworkSubmission implements Serializable {
    private static final long serialVersionUID = -8116066494190165108L;
    private Long id;
    private Long homeworkId;
    private Long studentId;
    private String content;
    private String status; // 提交状态
    private Integer score; // 总分
    private String answerData; // 学生提交的 JSON 字符串
    private String gradingData; // 批改结果 JSON 字符串

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // 关联附件列表 (非数据库字段)
    private List<AttachmentDetail> attachments;

    // 关联作业信息 (非数据库字段)
    private Homework homework;
}