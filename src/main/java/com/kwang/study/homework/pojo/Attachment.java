package com.kwang.study.homework.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Attachment implements Serializable {
    private static final long serialVersionUID = 6259950699782557678L;
    private Long id;
    private Long ownerId;
    private String ownerType;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private Integer mimeTypeId;
    private Long uploaderId;
    private LocalDateTime createTime;
}
