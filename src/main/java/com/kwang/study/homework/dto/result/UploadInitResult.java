package com.kwang.study.homework.dto.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UploadInitResult {
    private String originalFileName; // 返回原始文件名，便于前端匹配
    private String uploadId;         // 用于分块上传的ID
}
