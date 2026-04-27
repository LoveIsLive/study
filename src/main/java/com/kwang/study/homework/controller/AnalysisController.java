package com.kwang.study.homework.controller;

import com.kwang.study.common.R;
import com.kwang.study.constant.ApiPrefixConstant;
import com.kwang.study.homework.dto.result.analysis.CourseAnalyticsDTO;
import com.kwang.study.homework.dto.result.analysis.HomeworkAnalyticsDTO;
import com.kwang.study.homework.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPrefixConstant.API_BASE_PREFIX + "/analysis")
@RequiredArgsConstructor
@Validated
public class AnalysisController {

    private final AnalysisService analysisService;

    /**
     * 1. 课程宏观分析面板
     * 自动根据当前登录人角色返回对应的学生/教师分析数据
     */
    @GetMapping("/course/{courseId}")
    public ResponseEntity<R<CourseAnalyticsDTO>> getCourseAnalytics(@PathVariable Long courseId) {
        return ResponseEntity.ok(R.success(analysisService.getCourseAnalytics(courseId)));
    }

    /**
     * 2. 单次作业微观分析面板
     * 自动根据当前登录人角色返回对应的学生/教师分析数据
     */
    @GetMapping("/homework/{homeworkId}")
    public ResponseEntity<R<HomeworkAnalyticsDTO>> getHomeworkAnalytics(@PathVariable Long homeworkId) {
        return ResponseEntity.ok(R.success(analysisService.getHomeworkAnalytics(homeworkId)));
    }
}