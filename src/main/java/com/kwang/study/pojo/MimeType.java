package com.kwang.study.pojo;

import lombok.Data;

import java.io.Serializable;

/**
 * 对应 mime_types 表的实体类
 */
@Data
public class MimeType implements Serializable {
    private static final long serialVersionUID = -7370386777943619944L;
    /**
     * 类型ID
     */
    private Integer id;

    /**
     * MIME类型名称
     */
    private String name;
}