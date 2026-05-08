package com.kwang.study.fs.pojo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对应 node 表的实体类
 */
@Data
public class Node implements Serializable {
    private static final long serialVersionUID = -2186098855323172684L;
    /**
     * 路径项的id
     */
    private Long id;

    /**
     * 路径项的父id，根节点的父为NULL
     */
    private Long parentId;

    /**
     * 路径项的名称，同一目录下名称唯一
     */
    private String name;

    /**
     * 目录项的类型，0为目录，1为文件
     */
    private Integer type;

    private Integer isHidden; // 0:公开, 1:隐藏

    /**
     * 路径项的大小，以字节为单位，目录设置0
     */
    private Long size;

    /**
     * 路径项的创建时间
     */
    private LocalDateTime createTime;

    /**
     * 路径项的修改时间
     */
    private LocalDateTime modifyTime;

    /**
     * 关联到 hash_ref_num 表的ID，目录或空文件为NULL
     */
    private Long hashId;

    /**
     * 文件的mime类型ID
     */
    private Integer mimeTypeId;
}