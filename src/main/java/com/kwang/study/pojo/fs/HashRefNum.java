package com.kwang.study.pojo.fs;

import lombok.Data;

import java.io.Serializable;

/**
 * 对应 hash_ref_num 表的实体类
 */
@Data
public class HashRefNum implements Serializable {
    private static final long serialVersionUID = 3207416245986064959L;
    /**
     * 代理主键ID
     */
    private Long id;

    /**
     * 文件内容的sha-256 hash码
     */
    private String hash;

    /**
     * 文件的实际存储位置
     */
    private String refPath;

    /**
     * 文件的引用数量
     */
    private Integer refNum;

    /**
     * 文件的大小，以字节为单位
     */
    private Long size;
}