package com.kwang.study.homework.dto.result.analysis;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class CourseAnalyticsDTO {
    private String role; // "STUDENT" 或 "TEACHER"

    // 顶部 KPI
    private String submissionRate;  // 提交率 (学生: 自己提交率; 教师: 全班平均提交率)
    private Integer averageScore;   // 平均得分

    // 教师端特有：异动预警名单 (学生名 -> 预警原因)
    private Map<String, String> warningStudents;

    // 学生端特有：均分差
    private Integer diffWithClassAverage;

    // 历次作业趋势 (折线图数据)
    private List<TrendItemDTO> trends;

    // 【扩展预留】能力雷达图数据
    private Map<String, Integer> radarData;
}