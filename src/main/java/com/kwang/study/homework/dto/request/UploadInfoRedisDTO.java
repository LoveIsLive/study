package com.kwang.study.homework.dto.request;

import lombok.Builder;
import lombok.Data;
import java.io.Serializable;

@Data
@Builder
public class UploadInfoRedisDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String filePath;
    private String fileName;
    private Long fileSize;
    private String mimeTypeName;
    private String uploaderId;
}
