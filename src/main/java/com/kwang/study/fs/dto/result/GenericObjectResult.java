package com.kwang.study.fs.dto.result;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class GenericObjectResult extends BaseResult {
    /**
     * 系统内部使用的节点主键，不对外暴露，防止越权枚举
     */
    @JsonIgnore
    private Long id;
    /**
     * 文件的名称，同一目录下名称唯一
     */
    private String name;
    /**
     * 对象类型，0为目录，1为文件
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

}
