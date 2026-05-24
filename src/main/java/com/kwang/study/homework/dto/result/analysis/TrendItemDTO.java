package com.kwang.study.homework.dto.result.analysis;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TrendItemDTO {
    private Long homeworkId;
    private String homeworkTitle;
    private Integer myScore;        // 我的得分 (学生视图)
    private Integer classAverage;   // 班级平均分
    private Integer highestScore;   // 最高分
    private Integer submissionCount;// 提交人数 (教师视图)
    private Integer fullScore;      // 满分分值
}