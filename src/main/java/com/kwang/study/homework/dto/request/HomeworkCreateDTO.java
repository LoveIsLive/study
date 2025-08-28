package com.kwang.study.homework.dto.request;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.List;

@Data
public class HomeworkCreateDTO {
    /**
     * 教师ID，从当前登陆用户获取，非前端传入
     */
    private Long teacherId;

    @NotBlank(message = "Title is mandatory")
    @Size(max = 255, message = "Title cannot exceed 255 characters")
    private String title;

    private String content;

    private List<String> attachmentUploadIds; // 大附件的uploadId, 小附件不使用此
}
