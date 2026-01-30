package com.kwang.study.homework.dto.json;
import lombok.Data;
import org.hibernate.validator.constraints.Range;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.List;

@Data
public class HomeworkMetaDTO implements Serializable {
    private static final long serialVersionUID = -275841937305737705L;

    @NotNull(message = "总分值不能为空")
    @Range(min = 1, message = "总分值必须大于1")
    private Integer totalScore;

    @NotEmpty(message = "作业必须包含至少一道题目")
    @Valid
    private List<QuestionItemDTO> questions;
}