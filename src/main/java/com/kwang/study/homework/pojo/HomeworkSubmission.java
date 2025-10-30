package com.kwang.study.homework.pojo;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class HomeworkSubmission {
    private Long id;
    private Long homeworkId;
    private Long studentId;
    private String content;
    private String status; // 提交状态
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // 关联附件列表 (非数据库字段)
    private List<AttachmentDetail> attachments;

    // 关联作业信息 (非数据库字段)
    private Homework homework;
}