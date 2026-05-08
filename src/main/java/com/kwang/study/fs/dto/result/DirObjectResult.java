package com.kwang.study.fs.dto.result;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class DirObjectResult extends GenericObjectResult {
    /**
     * 目录内容
     */
    private List<FileObjectDesc> fileObjectDescs;

    @Data
    public static class FileObjectDesc {
        /**
         * 文件的名称，同一目录下名称唯一
         */
        private String name;
        /**
         * 通用对象类型，0为目录，1为文件
         */
        private Integer type;

        private Integer isHidden;

        /**
         * 对象的创建时间
         */
        private LocalDateTime createTime;

        /**
         * 对象的修改时间
         */
        private LocalDateTime modifyTime;

        /**
         * 路径项的大小，以字节为单位，目录设置0
         */
        private Long size;

        /**
         * 文件的mime类型名称
         */
        private String mimeTypeName;
    }
}
