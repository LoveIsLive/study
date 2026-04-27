package com.kwang.study.homework.dto.result.analysis;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class HomeworkAnalyticsDTO {
    private String role;

    // 基础指标
    private Integer classAverage;
    private Integer classHighest;
    private Integer myScore; // 学生特有

    // 教师端：成绩分布饼图/柱状图数据 (优秀、良好、及格、不及格)
    private Map<String, Integer> scoreDistribution;

    // 专属错题集 (学生：我做错的题；教师：全班错误率最高的Top 5)
    private List<WrongQuestionItemDTO> wrongQuestions;
}