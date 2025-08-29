package com.kwang.study.homework.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadInfoRedisDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String filePath;
    private String fileName;
    private Long fileSize;
    private String mimeTypeName;
    private String uploaderId;
}
