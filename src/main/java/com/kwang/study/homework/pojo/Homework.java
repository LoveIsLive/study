package com.kwang.study.homework.pojo;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class Homework {
    private Long id;
    /**
     * TODO: 返回给前端的应该是详情，包括教师名称
     */
    private Long teacherId;
    private String title;
    private String content;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // 关联附件列表 (非数据库字段)
    private List<AttachmentDetail> attachments;
}
