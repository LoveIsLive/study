package com.kwang.study.ware.dto.request;

import com.kwang.study.dto.BaseRequestDTO;
import com.kwang.study.utils.PathUtils;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.util.Assert;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

@Data
@EqualsAndHashCode(callSuper = true)
public class SearchRequestDTO extends BaseRequestDTO {
    private static final long serialVersionUID = 5178821672672044621L;

    private String path;

    @NotBlank(message = "搜索名称不能为空")
    @Pattern(regexp = "^[^/\\\\:*?\"<>|]+$", message = "搜索名称不能包含非法字符")
    private String namePattern; // 模糊查询的名称模式

    private Long activeClassId;
    private Long activeSchoolId;

    @Override
    public void check() {
        super.check();
        Assert.isTrue(PathUtils.isValidPath(path), "路径非法:" + path);
    }
}
