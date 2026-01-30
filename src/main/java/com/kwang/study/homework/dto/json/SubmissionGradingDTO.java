package com.kwang.study.homework.dto.json;

import lombok.Data;
import javax.validation.constraints.NotNull;
import java.util.Map;

@Data
public class SubmissionGradingDTO {
    @NotNull
    private Long submissionId;

    // 总评语
    private String generalComment;
    // 总评分
    private Integer manualTotalScore;

    // 每一题的批改详情：Key 是 questionId
    private Map<String, QuestionGradingItem> details;

    @Data
    public static class QuestionGradingItem {
        private Integer score;   // 该题得分
        private String comment;  // 该题评语
    }
}