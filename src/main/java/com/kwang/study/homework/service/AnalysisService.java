package com.kwang.study.homework.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.auth.utils.AuthenticationUserUtil;
import com.kwang.study.auth.utils.UserInfoUtils;
import com.kwang.study.course.mapper.CourseMapper;
import com.kwang.study.course.pojo.Course;
import com.kwang.study.homework.dto.json.HomeworkMetaDTO;
import com.kwang.study.homework.dto.json.QuestionItemDTO;
import com.kwang.study.homework.dto.json.SubmissionGradingDTO;
import com.kwang.study.homework.dto.result.analysis.CourseAnalyticsDTO;
import com.kwang.study.homework.dto.result.analysis.HomeworkAnalyticsDTO;
import com.kwang.study.homework.dto.result.analysis.TrendItemDTO;
import com.kwang.study.homework.dto.result.analysis.WrongQuestionItemDTO;
import com.kwang.study.homework.mapper.HomeworkMapper;
import com.kwang.study.homework.mapper.HomeworkSubmissionMapper;
import com.kwang.study.homework.pojo.HomeworkDetail;
import com.kwang.study.homework.pojo.HomeworkSubmissionDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final HomeworkMapper homeworkMapper;
    private final HomeworkSubmissionMapper submissionMapper;
    private final CourseMapper courseMapper;
    private final UserInfoUtils userInfoUtils;
    private final ObjectMapper objectMapper;

    // ================== 1. 课程级别分析 ==================
    public CourseAnalyticsDTO getCourseAnalytics(Long courseId) {
        Course course = courseMapper.findById(courseId);
        Assert.notNull(course, "课程不存在");

        List<HomeworkDetail> homeworks = homeworkMapper.findAllByCourseId(courseId);
        // 按创建时间升序，保障折线图时间轴的正确性
        homeworks.sort(Comparator.comparing(HomeworkDetail::getCreateTime));

        if (userInfoUtils.currentUserInClassIsStudent()) {
            return buildStudentCourseAnalytics(homeworks);
        } else {
            // Admin, 教师, 校长 看大盘
            return buildTeacherCourseAnalytics(homeworks);
        }
    }

    // ================== 2. 作业级别分析 ==================
    public HomeworkAnalyticsDTO getHomeworkAnalytics(Long homeworkId) {
        HomeworkDetail homework = homeworkMapper.findById(homeworkId);
        Assert.notNull(homework, "作业不存在");
        List<HomeworkSubmissionDetail> allSubmissions = submissionMapper.findAllByHomeworkId(homeworkId);

        if (userInfoUtils.currentUserInClassIsStudent()) {
            return buildStudentHomeworkAnalytics(homework, allSubmissions);
        } else {
            return buildTeacherHomeworkAnalytics(homework, allSubmissions);
        }
    }

    // ================== 私有构建方法 (学生-课程) ==================
    private CourseAnalyticsDTO buildStudentCourseAnalytics(List<HomeworkDetail> homeworks) {
        Long studentId = AuthenticationUserUtil.getCurrentUserId();
        int totalHomeworks = homeworks.size();
        int submittedCount = 0;
        int myTotalScore = 0;
        int classTotalScore = 0;
        int gradedHomeworkCount = 0;

        List<TrendItemDTO> trends = new ArrayList<>();

        // --- 雷达图能力聚合池 ---
        Map<String, Integer> typeEarnedScore = new HashMap<>(); // 某个维度的实际得分
        Map<String, Integer> typeTotalScore = new HashMap<>();  // 某个维度的总应得分

        for (HomeworkDetail hw : homeworks) {
            List<HomeworkSubmissionDetail> subs = submissionMapper.findAllByHomeworkId(hw.getId());
            Optional<HomeworkSubmissionDetail> mySub = subs.stream()
                    .filter(s -> s.getStudentId().equals(studentId))
                    .findFirst();

            if (mySub.isPresent()) submittedCount++;

            // 仅统计已批改的作业作为成绩趋势
            int classAvg = subs.isEmpty() ? 0 : (int) subs.stream().filter(s -> s.getScore() != null)
                    .mapToInt(HomeworkSubmissionDetail::getScore).average().orElse(0);

            Integer myScore = mySub.map(HomeworkSubmissionDetail::getScore).orElse(null);

            trends.add(TrendItemDTO.builder()
                    .homeworkId(hw.getId())
                    .homeworkTitle(hw.getTitle())
                    .myScore(myScore)
                    .classAverage(classAvg)
                    .build());

            if (myScore != null) {
                myTotalScore += myScore;
                classTotalScore += classAvg;
                gradedHomeworkCount++;

                // 【核心升级：解析雷达图数据】
                if ("STRUCTURED".equals(hw.getType()) && StringUtils.hasText(hw.getMetaData())
                        && StringUtils.hasText(mySub.get().getGradingData())) {
                    try {
                        HomeworkMetaDTO meta = objectMapper.readValue(hw.getMetaData(), HomeworkMetaDTO.class);
                        SubmissionGradingDTO grading = objectMapper.readValue(mySub.get().getGradingData(), SubmissionGradingDTO.class);

                        // 构建题目元数据索引
                        Map<String, QuestionItemDTO> qMap = meta.getQuestions().stream()
                                .collect(Collectors.toMap(QuestionItemDTO::getId, q -> q));

                        if (grading.getDetails() != null) {
                            for (Map.Entry<String, SubmissionGradingDTO.QuestionGradingItem> entry : grading.getDetails().entrySet()) {
                                String qId = entry.getKey();
                                Integer earned = entry.getValue().getScore();
                                QuestionItemDTO qMeta = qMap.get(qId);

                                if (qMeta != null && earned != null && qMeta.getScore() != null) {
                                    // 将底层题型转化为前端易懂的能力维度
                                    String abilityDimension = translateTypeToAbility(qMeta.getType());
                                    typeEarnedScore.put(abilityDimension, typeEarnedScore.getOrDefault(abilityDimension, 0) + earned);
                                    typeTotalScore.put(abilityDimension, typeTotalScore.getOrDefault(abilityDimension, 0) + qMeta.getScore());
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("学生雷达图能力数据解析失败", e);
                    }
                }
            }
        }

        // 计算各维度最终胜率 (%) 用于雷达图渲染
        Map<String, Integer> radarData = new HashMap<>();
        for (Map.Entry<String, Integer> entry : typeTotalScore.entrySet()) {
            String dimension = entry.getKey();
            int total = entry.getValue();
            int earned = typeEarnedScore.getOrDefault(dimension, 0);
            radarData.put(dimension, total == 0 ? 0 : (int) Math.round((earned * 100.0) / total));
        }

        int myAvg = gradedHomeworkCount == 0 ? 0 : myTotalScore / gradedHomeworkCount;
        int classAvgTotal = gradedHomeworkCount == 0 ? 0 : classTotalScore / gradedHomeworkCount;

        return CourseAnalyticsDTO.builder()
                .role("STUDENT")
                .submissionRate(totalHomeworks == 0 ? "0%" : (submittedCount * 100 / totalHomeworks) + "%")
                .averageScore(myAvg)
                .diffWithClassAverage(myAvg - classAvgTotal)
                .trends(trends)
                .radarData(radarData) // 组装完成的雷达图数据！
                .build();
    }

    // ================== 私有构建方法 (教师-课程) ==================
    private CourseAnalyticsDTO buildTeacherCourseAnalytics(List<HomeworkDetail> homeworks) {
        List<TrendItemDTO> trends = new ArrayList<>();
        int totalScoreAccumulator = 0;
        int validHomeworks = 0;

        for (HomeworkDetail hw : homeworks) {
            List<HomeworkSubmissionDetail> subs = submissionMapper.findAllByHomeworkId(hw.getId());

            int classAvg = subs.isEmpty() ? 0 : (int) subs.stream().filter(s -> s.getScore() != null)
                    .mapToInt(HomeworkSubmissionDetail::getScore).average().orElse(0);
            int highest = subs.isEmpty() ? 0 : subs.stream().filter(s -> s.getScore() != null)
                    .mapToInt(HomeworkSubmissionDetail::getScore).max().orElse(0);

            trends.add(TrendItemDTO.builder()
                    .homeworkId(hw.getId())
                    .homeworkTitle(hw.getTitle())
                    .classAverage(classAvg)
                    .highestScore(highest)
                    .submissionCount(subs.size())
                    .build());

            if (classAvg > 0) {
                totalScoreAccumulator += classAvg;
                validHomeworks++;
            }
        }

        return CourseAnalyticsDTO.builder()
                .role("TEACHER")
                .averageScore(validHomeworks == 0 ? 0 : totalScoreAccumulator / validHomeworks)
                .trends(trends)
                .build();
    }

    // ================== 私有构建方法 (学生-作业) ==================
    private HomeworkAnalyticsDTO buildStudentHomeworkAnalytics(HomeworkDetail homework, List<HomeworkSubmissionDetail> allSubmissions) {
        Long studentId = AuthenticationUserUtil.getCurrentUserId();

        int classAvg = allSubmissions.isEmpty() ? 0 : (int) allSubmissions.stream().filter(s -> s.getScore() != null)
                .mapToInt(HomeworkSubmissionDetail::getScore).average().orElse(0);
        int highest = allSubmissions.isEmpty() ? 0 : allSubmissions.stream().filter(s -> s.getScore() != null)
                .mapToInt(HomeworkSubmissionDetail::getScore).max().orElse(0);

        HomeworkSubmissionDetail mySub = allSubmissions.stream()
                .filter(s -> s.getStudentId().equals(studentId)).findFirst().orElse(null);

        List<WrongQuestionItemDTO> wrongQuestions = new ArrayList<>();

        // 解析学生错题 (仅支持结构化作业)
        if (mySub != null && "STRUCTURED".equals(homework.getType()) && StringUtils.hasText(mySub.getGradingData())) {
            try {
                HomeworkMetaDTO meta = objectMapper.readValue(homework.getMetaData(), HomeworkMetaDTO.class);
                Map<String, QuestionItemDTO> qMap = meta.getQuestions().stream()
                        .collect(Collectors.toMap(QuestionItemDTO::getId, q -> q));

                SubmissionGradingDTO grading = objectMapper.readValue(mySub.getGradingData(), SubmissionGradingDTO.class);

                if (grading.getDetails() != null) {
                    for (Map.Entry<String, SubmissionGradingDTO.QuestionGradingItem> entry : grading.getDetails().entrySet()) {
                        String qId = entry.getKey();
                        Integer myScore = entry.getValue().getScore();
                        QuestionItemDTO qMeta = qMap.get(qId);

                        // 错题判定：得分小于满分
                        if (qMeta != null && myScore != null && myScore < qMeta.getScore()) {
                            wrongQuestions.add(WrongQuestionItemDTO.builder()
                                    .questionId(qId)
                                    .title(qMeta.getTitle())
                                    .type(qMeta.getType())
                                    .fullScore(qMeta.getScore())
                                    .myScore(myScore)
                                    .aiComment(entry.getValue().getComment())
                                    .build());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("解析学生错题本失败", e);
            }
        }

        return HomeworkAnalyticsDTO.builder()
                .role("STUDENT")
                .classAverage(classAvg)
                .classHighest(highest)
                .myScore(mySub == null ? null : mySub.getScore())
                .wrongQuestions(wrongQuestions)
                .build();
    }

    // ================== 私有构建方法 (教师-作业) ==================
    private HomeworkAnalyticsDTO buildTeacherHomeworkAnalytics(HomeworkDetail homework, List<HomeworkSubmissionDetail> allSubmissions) {
        int classAvg = 0, highest = 0;
        Map<String, Integer> distribution = new LinkedHashMap<>();
        distribution.put("优秀(90-100%)", 0);
        distribution.put("良好(80-89%)", 0);
        distribution.put("及格(60-79%)", 0);
        distribution.put("不及格(<60%)", 0);

        // 提取本次作业的实际总分 (默认兜底为100)
        int homeworkTotalScore = 100;
        HomeworkMetaDTO meta = null;
        if ("STRUCTURED".equals(homework.getType()) && StringUtils.hasText(homework.getMetaData())) {
            try {
                meta = objectMapper.readValue(homework.getMetaData(), HomeworkMetaDTO.class);
                if (meta.getTotalScore() != null) {
                    homeworkTotalScore = meta.getTotalScore();
                }
            } catch (Exception e) {
                log.error("解析作业元数据获取总分失败", e);
            }
        }

        // 高频错题统计：问题ID -> 错误次数
        Map<String, Integer> wrongCountMap = new HashMap<>();

        if (!allSubmissions.isEmpty()) {
            List<HomeworkSubmissionDetail> gradedSubs = allSubmissions.stream()
                    .filter(s -> s.getScore() != null).collect(Collectors.toList());

            if (!gradedSubs.isEmpty()) {
                classAvg = (int) gradedSubs.stream().mapToInt(HomeworkSubmissionDetail::getScore).average().orElse(0);
                highest = gradedSubs.stream().mapToInt(HomeworkSubmissionDetail::getScore).max().orElse(0);
            }

            // 计算分布并统计错题
            for (HomeworkSubmissionDetail sub : gradedSubs) {

                // 【核心升级：采用真实比例精确计算分数段分布】
                int score = sub.getScore();
                double ratio = (double) score / homeworkTotalScore;

                if (ratio >= 0.9) distribution.put("优秀(90-100%)", distribution.get("优秀(90-100%)") + 1);
                else if (ratio >= 0.8) distribution.put("良好(80-89%)", distribution.get("良好(80-89%)") + 1);
                else if (ratio >= 0.6) distribution.put("及格(60-79%)", distribution.get("及格(60-79%)") + 1);
                else distribution.put("不及格(<60%)", distribution.get("不及格(<60%)") + 1);

                // 2. 错题统计聚合
                if (meta != null && StringUtils.hasText(sub.getGradingData())) {
                    try {
                        Map<String, Integer> qScoreMap = meta.getQuestions().stream()
                                .collect(Collectors.toMap(QuestionItemDTO::getId, QuestionItemDTO::getScore));

                        SubmissionGradingDTO grading = objectMapper.readValue(sub.getGradingData(), SubmissionGradingDTO.class);
                        if (grading.getDetails() != null) {
                            for (Map.Entry<String, SubmissionGradingDTO.QuestionGradingItem> entry : grading.getDetails().entrySet()) {
                                String qId = entry.getKey();
                                Integer myScore = entry.getValue().getScore();
                                Integer fullScore = qScoreMap.get(qId);

                                // 失分即记为一次错误
                                if (fullScore != null && myScore != null && myScore < fullScore) {
                                    wrongCountMap.put(qId, wrongCountMap.getOrDefault(qId, 0) + 1);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("聚合教师端错题数据失败", e);
                    }
                }
            }
        }

        // 3. 构建高频错题榜单 (Top 5)
        List<WrongQuestionItemDTO> topWrongQuestions = new ArrayList<>();
        if (!wrongCountMap.isEmpty() && meta != null) {
            Map<String, QuestionItemDTO> qMap = meta.getQuestions().stream()
                    .collect(Collectors.toMap(QuestionItemDTO::getId, q -> q));

            int totalSubs = allSubmissions.size();

            topWrongQuestions = wrongCountMap.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(5)
                    .map(entry -> {
                        QuestionItemDTO qMeta = qMap.get(entry.getKey());
                        return WrongQuestionItemDTO.builder()
                                .questionId(entry.getKey())
                                .title(qMeta != null ? qMeta.getTitle() : "未知题目")
                                .type(qMeta != null ? qMeta.getType() : "")
                                .wrongCount(entry.getValue())
                                .errorRate(totalSubs == 0 ? "0%" : Math.round((entry.getValue() * 100.0) / totalSubs) + "%")
                                .build();
                    })
                    .collect(Collectors.toList());
        }

        return HomeworkAnalyticsDTO.builder()
                .role("TEACHER")
                .classAverage(classAvg)
                .classHighest(highest)
                .scoreDistribution(distribution) // 完美贴合真实比例的饼图数据
                .wrongQuestions(topWrongQuestions)
                .build();
    }

    /**
     * 将底层题型映射为大白话的能力维度（支撑雷达图）
     */
    private String translateTypeToAbility(String type) {
        if ("SINGLE_CHOICE".equals(type)) return "基础知识(单选)";
        if ("MULTI_CHOICE".equals(type)) return "综合辨析(多选)";
        if ("TEXT".equals(type)) return "逻辑与表达(简答)";
        return "其他";
    }
}