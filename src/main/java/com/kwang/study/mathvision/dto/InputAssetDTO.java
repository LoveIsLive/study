package com.kwang.study.mathvision.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 输入资产条目, 序列化进 mathvision_tasks.input_assets_json。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InputAssetDTO {
    private String fileName;
    private String filePath;
    private String mimeTypeName;
    private Long fileSize;
    /** multipart / uploadFiles */
    private String source;
}
