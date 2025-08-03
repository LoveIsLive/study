package com.kwang.study.dto.fs.request;

import com.kwang.study.dto.BaseRequestDTO;
import com.kwang.study.utils.DataCheckUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

@Data
@EqualsAndHashCode(callSuper = true)
public class UploadFileRequestDTO extends BaseRequestDTO {
    private static final long serialVersionUID = -3196100301224337052L;

    @NotBlank(message = "文件名不能为空")
    @Pattern(regexp = "^[^/\\\\:*?\"<>|]+$", message = "文件名不能包含非法字符")
    private String name;

    private Long parentId;

    @NotNull(message = "文件不能为空")
    private MultipartFile file;

    private String permissions;

    @NotBlank(message = "文件类型不能为空")
    private String mimeTypeName;

    @Override
    public void check() {
        super.check();
        Assert.isTrue(DataCheckUtil.checkPermissions(permissions), "权限格式不正确");
    }
}
