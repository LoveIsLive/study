package com.kwang.study.homework.pojo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class Homework implements Serializable {
    private static final long serialVersionUID = -4986478159093212416L;

    private Long id;
    private Long teacherId;
    private String title;
    private String content;
    private String type; // "SIMPLE" or "STRUCTURED"
    /**
     * 存储 JSON 字符串。
     * 结构示例:
     * {
     *   "questions": [
     *      {"id": "q1", "type": "single", "title": "...", "options": [...], "score": 5, "correct": "A"}
     *   ]
     * }
     */
    private String metaData;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // 关联附件列表 (非数据库字段)
    private List<AttachmentDetail> attachments;
}
