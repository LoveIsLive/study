package com.kwang.study.homework.dto.json;
import lombok.Data;
import org.hibernate.validator.constraints.Range;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.io.Serializable;
import java.util.List;

@Data
public class QuestionItemDTO implements Serializable {
    private static final long serialVersionUID = -3554956523770297300L;

    @NotBlank(message = "题目ID不能为空")
    private String id;

    @NotBlank(message = "题型不能为空")
    @Pattern(regexp = "^(SINGLE_CHOICE|MULTI_CHOICE|TEXT)$", message = "不支持的题型")
    private String type;

    @NotBlank(message = "题干不能为空")
    private String title;

    @NotNull(message = "分值不能为空")
    @Range(min = 1, message = "单题分值必须大于1")
    private Integer score;

    @Valid
    private List<QuestionOptionDTO> options;

    // 为了通用性，正确答案使用 Object 接收，在 Validator 中根据 type 强转校验
    // 单选: String (OptionID)
    // 多选: List<String> (OptionIDs)
    // 文本: String (参考答案)
    private Object correctAnswer;

    private String analysis; // 解析
    private String aiGradingCriteria; // AI 评分标准
}