package com.kwang.study.dto.fs.request;

import com.kwang.study.dto.BaseRequestDTO;
import com.kwang.study.utils.DataCheckUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.util.Assert;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import java.util.Set;

@Data
@EqualsAndHashCode(callSuper = true)
public class CreateDirectoryDTO extends BaseRequestDTO {
    private static final long serialVersionUID = -8646614385703462086L;

    @NotBlank(message = "目录名称不能为空")
    @Pattern(regexp = "^[^/\\\\:*?\"<>|]+$", message = "目录名称不能包含非法字符")
    private String name;

    private Long parentId;

    private String permissions;

    @Override
    public void check() {
        super.check();
        Assert.isTrue(DataCheckUtil.checkPermissions(permissions), "权限格式不正确");
    }
}
