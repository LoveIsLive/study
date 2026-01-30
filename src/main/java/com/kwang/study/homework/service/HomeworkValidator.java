package com.kwang.study.homework.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.homework.dto.json.HomeworkMetaDTO;
import com.kwang.study.homework.dto.json.QuestionItemDTO;
import com.kwang.study.homework.dto.json.QuestionOptionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;


import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class HomeworkValidator {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Validator validator;

    /**
     * 校验发布作业时的元数据
     */
    public void validateHomeworkMeta(HomeworkMetaDTO meta) {
        if (meta == null) return;

        // 验证javax注解
        Set<ConstraintViolation<HomeworkMetaDTO>> validate = validator.validate(meta);
        if (!validate.isEmpty()) {
            // 处理错误
            for (ConstraintViolation<HomeworkMetaDTO> v : validate) {
                log.error("{} {}", v.getPropertyPath(), v.getMessage());
            }
            throw new RuntimeException("校验不通过");
        }

        Set<String> questionIds = new java.util.HashSet<>();
        int calculatedScore = 0;

        for (QuestionItemDTO q : meta.getQuestions()) {
            Assert.isTrue(questionIds.add(q.getId()), "题目ID重复: " + q.getId());
            calculatedScore += (q.getScore() == null ? 0 : q.getScore());

            if ("SINGLE_CHOICE".equals(q.getType()) || "MULTI_CHOICE".equals(q.getType())) {
                validateChoiceQuestion(q);
            }
        }
        // 也可以校验 totalScore 是否等于 calculatedScore
        Assert.isTrue(calculatedScore == meta.getTotalScore(), "各个题目分值和与总分值不相等");
    }


    /**
     * 校验学生提交的答案
     */
    public void validateSubmission(String homeworkJson, Map<String, Object> answerData) {
        if (answerData == null || answerData.isEmpty()) return;

        try {
            HomeworkMetaDTO meta = objectMapper.readValue(homeworkJson, HomeworkMetaDTO.class);
            Map<String, QuestionItemDTO> qMap = meta.getQuestions().stream()
                    .collect(Collectors.toMap(QuestionItemDTO::getId, q -> q));

            for (Map.Entry<String, Object> entry : answerData.entrySet()) {
                String qId = entry.getKey();
                Object val = entry.getValue();

                QuestionItemDTO q = qMap.get(qId);
                if (q == null) continue; // 忽略无关字段

                if ("MULTI_CHOICE".equals(q.getType())) {
                    if (val != null && !(val instanceof List)) {
                        throw new IllegalArgumentException("题目 [" + q.getTitle() + "] 答案格式应为数组");
                    }
                } else {
                    if (val != null && !(val instanceof String)) {
                        throw new IllegalArgumentException("题目 [" + q.getTitle() + "] 答案格式应为字符串");
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("答案校验失败: " + e.getMessage());
        }
    }

    private void validateChoiceQuestion(QuestionItemDTO q) {
        Assert.notEmpty(q.getOptions(), "选择题 [" + q.getTitle() + "] 必须包含选项");

        Set<String> optionIds = q.getOptions().stream()
                .map(QuestionOptionDTO::getId)
                .collect(Collectors.toSet());
        Assert.isTrue(optionIds.size() == q.getOptions().size(), "题目 [" + q.getTitle() + "] 选项ID重复");

        Object answer = q.getCorrectAnswer();
        if (answer == null) return; // 允许暂无答案

        if ("SINGLE_CHOICE".equals(q.getType())) {
            Assert.isTrue(answer instanceof String, "单选题答案格式错误");
            Assert.isTrue(optionIds.contains(answer), "单选题答案不在选项中: " + q.getTitle());
        } else {
            Assert.isTrue(answer instanceof List, "多选题答案格式错误");
            List<?> list = (List<?>) answer;
            for (Object id : list) {
                Assert.isTrue(optionIds.contains(id), "多选题答案不在选项中: " + q.getTitle());
            }
        }
    }

}