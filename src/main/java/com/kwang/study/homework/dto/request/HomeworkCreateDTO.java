package com.kwang.study.homework.dto.request;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.List;
import java.util.Map;

@Data
public class HomeworkCreateDTO {
    @NotBlank(message = "Title is mandatory")
    @Size(max = 255, message = "Title cannot exceed 255 characters")
    private String title;

    private String content;

    private String type = "SIMPLE"; // 默认为普通模式

    /**
     * 前端传过来是一个 JSON 对象，Spring 会自动转为 Map。
     * 后续我们将它转为 String 存入数据库。
     */
    private Map<String, Object> metaData;

    private List<String> attachmentUploadIds; // 大附件的uploadId, 小附件不使用此
}
