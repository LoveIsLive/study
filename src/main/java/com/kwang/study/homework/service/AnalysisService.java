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
import com.kwang.study.organization.enums.ClassesRoleEnum;
import com.kwang.study.organization.mapper.ClassMemberMapper;
import com.kwang.study.organization.pojo.ClassMember;
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
    private final ClassMemberMapper classMemberMapper;

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
            return buildTeacherCourseAnalytics(courseId, course.getClassId(), homeworks);
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

            if (mySub.isPresent()) {
                submittedCount++;
            }

            // 仅统计已批改的作业作为成绩趋势
            int classAvg = subs.isEmpty() ? 0 : (int) subs.stream()
                    .filter(s -> s.getScore() != null)
                    .mapToInt(HomeworkSubmissionDetail::getScore)
                    .average()
                    .orElse(0);

            Integer myScore = mySub.map(HomeworkSubmissionDetail::getScore).orElse(null);

            trends.add(TrendItemDTO.builder()
                    .homeworkId(hw.getId())
                    .homeworkTitle(hw.getTitle())
                    .myScore(myScore)
                    .classAverage(classAvg)
                    .fullScore(getHomeworkTotalScore(hw))
                    .build());

            if (myScore != null) {
                myTotalScore += myScore;
                classTotalScore += classAvg;
                gradedHomeworkCount++;

                // 解析雷达图数据
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
                                    // 将底层题型转化为能力维度
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
                .radarData(radarData)
                .build();
    }

    // ================== 私有构建方法 (教师-课程) ==================
    private CourseAnalyticsDTO buildTeacherCourseAnalytics(Long courseId, Long classId, List<HomeworkDetail> homeworks) {
        List<TrendItemDTO> trends = new ArrayList<>();
        int totalScoreAccumulator = 0;
        int validHomeworks = 0;

        // 获取该班级下的所有学生名册
        List<ClassMember> students = new ArrayList<>();
        try {
            students = classMemberMapper.findUsersByClassIdAndRole(classId, ClassesRoleEnum.STUDENT.getRole());
        } catch (Exception e) {
            log.error("获取班级学生名册异常, classId: {}", classId, e);
        }

        int totalExpectedSubmissions = homeworks.size() * students.size();
        int totalActualSubmissions = 0;

        // 初始化学生提交映射，用于后续分析异动预警
        Map<Long, List<HomeworkSubmissionDetail>> studentSubmissionsMap = new HashMap<>();
        for (ClassMember student : students) {
            studentSubmissionsMap.put(student.getUserId(), new ArrayList<>());
        }

        for (HomeworkDetail hw : homeworks) {
            List<HomeworkSubmissionDetail> subs = submissionMapper.findAllByHomeworkId(hw.getId());
            totalActualSubmissions += subs.size();

            int classAvg = subs.isEmpty() ? 0 : (int) subs.stream()
                    .filter(s -> s.getScore() != null)
                    .mapToInt(HomeworkSubmissionDetail::getScore)
                    .average()
                    .orElse(0);
            int highest = subs.isEmpty() ? 0 : subs.stream()
                    .filter(s -> s.getScore() != null)
                    .mapToInt(HomeworkSubmissionDetail::getScore)
                    .max()
                    .orElse(0);

            trends.add(TrendItemDTO.builder()
                    .homeworkId(hw.getId())
                    .homeworkTitle(hw.getTitle())
                    .classAverage(classAvg)
                    .highestScore(highest)
                    .submissionCount(subs.size())
                    .fullScore(getHomeworkTotalScore(hw))
                    .build());

            if (classAvg > 0) {
                totalScoreAccumulator += classAvg;
                validHomeworks++;
            }

            // 按学生归集本次作业的提交记录
            for (HomeworkSubmissionDetail sub : subs) {
                if (studentSubmissionsMap.containsKey(sub.getStudentId())) {
                    studentSubmissionsMap.get(sub.getStudentId()).add(sub);
                }
            }
        }

        // 1. 计算全班平均提交率
        String submissionRate = "0%";
        if (totalExpectedSubmissions > 0) {
            submissionRate = Math.round((totalActualSubmissions * 100.0) / totalExpectedSubmissions) + "%";
        }

        // 2. 分析异动预警名单 (学生姓名 -> 预警原因)
        Map<String, String> warningStudents = new LinkedHashMap<>();
        int totalHomeworkCount = homeworks.size();

        if (totalHomeworkCount > 0 && !students.isEmpty()) {
            for (ClassMember student : students) {
                List<HomeworkSubmissionDetail> studentSubs = studentSubmissionsMap.get(student.getUserId());
                int subCount = studentSubs.size();
                double subRate = (double) subCount / totalHomeworkCount;

                String displayName = student.getUser().getUsername();
                if (!StringUtils.hasText(displayName)) {
                    displayName = "学生(ID:" + student.getUserId() + ")";
                }

                if (subCount == 0) {
                    warningStudents.put(displayName, "发布作业均未提交");
                } else if (subRate < 0.5) {
                    warningStudents.put(displayName, "作业缺交严重 (提交率 " + Math.round(subRate * 100) + "%)");
                } else {
                    // 分析已批改作业的平均得分比例
                    List<HomeworkSubmissionDetail> gradedSubs = studentSubs.stream()
                            .filter(s -> s.getScore() != null)
                            .collect(Collectors.toList());

                    if (!gradedSubs.isEmpty()) {
                        double earnedRatioSum = 0;
                        int gradedCount = 0;

                        for (HomeworkSubmissionDetail sub : gradedSubs) {
                            HomeworkDetail hw = homeworks.stream()
                                    .filter(h -> h.getId().equals(sub.getHomeworkId()))
                                    .findFirst()
                                    .orElse(null);
                            int hwTotal = hw != null ? getHomeworkTotalScore(hw) : 100;
                            earnedRatioSum += (double) sub.getScore() / hwTotal;
                            gradedCount++;
                        }

                        if (gradedCount > 0) {
                            double avgRatio = earnedRatioSum / gradedCount;
                            if (avgRatio < 0.6) {
                                warningStudents.put(displayName, "成绩持续走低 (平均得分率 " + Math.round(avgRatio * 100) + "%)");
                            }
                        }
                    }
                }
            }
        }

        return CourseAnalyticsDTO.builder()
                .role("TEACHER")
                .submissionRate(submissionRate)
                .warningStudents(warningStudents)
                .averageScore(validHomeworks == 0 ? 0 : totalScoreAccumulator / validHomeworks)
                .trends(trends)
                .build();
    }

    // ================== 私有构建方法 (学生-作业) ==================
    private HomeworkAnalyticsDTO buildStudentHomeworkAnalytics(HomeworkDetail homework, List<HomeworkSubmissionDetail> allSubmissions) {
        Long studentId = AuthenticationUserUtil.getCurrentUserId();

        int classAvg = allSubmissions.isEmpty() ? 0 : (int) allSubmissions.stream()
                .filter(s -> s.getScore() != null)
                .mapToInt(HomeworkSubmissionDetail::getScore)
                .average()
                .orElse(0);
        int highest = allSubmissions.isEmpty() ? 0 : allSubmissions.stream()
                .filter(s -> s.getScore() != null)
                .mapToInt(HomeworkSubmissionDetail::getScore)
                .max()
                .orElse(0);

        HomeworkSubmissionDetail mySub = allSubmissions.stream()
                .filter(s -> s.getStudentId().equals(studentId))
                .findFirst()
                .orElse(null);

        List<WrongQuestionItemDTO> wrongQuestions = new ArrayList<>();

        // 解析学生个人错题
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

                        // 失分即判定为错题
                        if (qMeta != null && myScore != null && myScore < qMeta.getScore()) {
                            wrongQuestions.add(WrongQuestionItemDTO.builder()
                                    .questionId(qId)
                                    .title(qMeta.getTitle())
                                    .type(translateTypeToChinese(qMeta.getType())) // 转换为中文类型
                                    .fullScore(qMeta.getScore())
                                    .myScore(myScore)
                                    .aiComment(entry.getValue().getComment())
                                    .build());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("解析学生错题集失败", e);
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

        int homeworkTotalScore = getHomeworkTotalScore(homework);
        Map<String, Integer> wrongCountMap = new HashMap<>();
        HomeworkMetaDTO meta = null;

        if ("STRUCTURED".equals(homework.getType()) && StringUtils.hasText(homework.getMetaData())) {
            try {
                meta = objectMapper.readValue(homework.getMetaData(), HomeworkMetaDTO.class);
            } catch (Exception e) {
                log.error("解析作业元数据失败", e);
            }
        }

        if (!allSubmissions.isEmpty()) {
            List<HomeworkSubmissionDetail> gradedSubs = allSubmissions.stream()
                    .filter(s -> s.getScore() != null)
                    .collect(Collectors.toList());

            if (!gradedSubs.isEmpty()) {
                classAvg = (int) gradedSubs.stream().mapToInt(HomeworkSubmissionDetail::getScore).average().orElse(0);
                highest = gradedSubs.stream().mapToInt(HomeworkSubmissionDetail::getScore).max().orElse(0);
            }

            for (HomeworkSubmissionDetail sub : gradedSubs) {
                int score = sub.getScore();
                double ratio = (double) score / homeworkTotalScore;

                if (ratio >= 0.9) distribution.put("优秀(90-100%)", distribution.get("优秀(90-100%)") + 1);
                else if (ratio >= 0.8) distribution.put("良好(80-89%)", distribution.get("良好(80-89%)") + 1);
                else if (ratio >= 0.6) distribution.put("及格(60-79%)", distribution.get("及格(60-79%)") + 1);
                else distribution.put("不及格(<60%)", distribution.get("不及格(<60%)") + 1);

                // 统计教师端错题数据
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

                                if (fullScore != null && myScore != null && myScore < fullScore) {
                                    wrongCountMap.put(qId, wrongCountMap.getOrDefault(qId, 0) + 1);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("聚合错题错因映射数据异常", e);
                    }
                }
            }
        }

        // 构建全班高频错题榜 (Top 5)
        List<WrongQuestionItemDTO> topWrongQuestions = new ArrayList<>();
        if (!wrongCountMap.isEmpty()) {
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
                                .type(qMeta != null ? translateTypeToChinese(qMeta.getType()) : "") // 汉化题型
                                .fullScore(qMeta != null ? qMeta.getScore() : 0) // 填充题目满分
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
                .scoreDistribution(distribution)
                .wrongQuestions(topWrongQuestions)
                .build();
    }

    /**
     * 辅助方法：统一解析获取作业设定的总分
     */
    private int getHomeworkTotalScore(HomeworkDetail hw) {
        if ("STRUCTURED".equals(hw.getType()) && StringUtils.hasText(hw.getMetaData())) {
            try {
                HomeworkMetaDTO meta = objectMapper.readValue(hw.getMetaData(), HomeworkMetaDTO.class);
                if (meta.getTotalScore() != null) {
                    return meta.getTotalScore();
                }
            } catch (Exception e) {
                log.error("解析作业总分失败, homeworkId: {}", hw.getId(), e);
            }
        }
        return 100; // 默认或者 SIMPLE 作业兜底总分为 100
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

    /**
     * 将英文题型标识映射为中文描述
     */
    private String translateTypeToChinese(String type) {
        if ("SINGLE_CHOICE".equals(type)) return "单选题";
        if ("MULTI_CHOICE".equals(type)) return "多选题";
        if ("TEXT".equals(type)) return "简答题";
        return "未知题型";
    }
}