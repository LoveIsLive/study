package com.kwang.study.dto.fs.request;

import com.kwang.study.dto.BaseRequestDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

@Data
@EqualsAndHashCode(callSuper = true)
public class SearchRequestDTO extends BaseRequestDTO {
    private static final long serialVersionUID = 5178821672672044621L;

    private Long startNodeId; // 从哪个目录开始搜索，为null则从根目录开始

    @NotBlank(message = "搜索名称不能为空")
    @Pattern(regexp = "^[^/\\\\:*?\"<>|]+$", message = "搜索名称不能包含非法字符")
    private String namePattern; // 模糊查询的名称模式
}
