package com.kwang.study.homework.dto.json;
import lombok.Data;
import javax.validation.constraints.NotBlank;
import java.io.Serializable;

@Data
public class QuestionOptionDTO implements Serializable {
    private static final long serialVersionUID = -6871829029384176584L;

    @NotBlank(message = "选项ID不能为空")
    private String id;
    private String label; // A, B, C...
    @NotBlank(message = "选项内容不能为空")
    private String text;
}