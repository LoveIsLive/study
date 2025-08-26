package com.kwang.study.homework.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Attachment {
    private Long id;
    private Long ownerId;
    private String ownerType;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private String mimeType;
    private Long uploaderId;
    private LocalDateTime createTime;
}
