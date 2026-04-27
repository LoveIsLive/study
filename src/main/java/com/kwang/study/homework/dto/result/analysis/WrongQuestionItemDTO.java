package com.kwang.study.homework.dto.result.analysis;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WrongQuestionItemDTO {
    private String questionId;
    private String title;          // 题干摘要
    private String type;           // 题型
    private Integer fullScore;     // 满分

    // 学生专属字段
    private Integer myScore;       // 我的得分
    private String aiComment;      // AI批语

    // 教师专属字段
    private Integer wrongCount;    // 全班错误人数
    private String errorRate;      // 错误率 (如 "45%")
}