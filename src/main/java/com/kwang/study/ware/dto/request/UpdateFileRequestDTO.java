package com.kwang.study.ware.dto.request;

import com.kwang.study.dto.BaseRequestDTO;
import com.kwang.study.utils.PathUtils;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
@EqualsAndHashCode(callSuper = true)
public class UpdateFileRequestDTO extends BaseRequestDTO {
    private static final long serialVersionUID = -3196100301224337052L;

    private String path;

    private String newName;

    private MultipartFile file;

    private String mimeTypeName;

    @Override
    public void check() {
        super.check();
        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法:" + path);
        Assert.isTrue(newName != null || file != null || mimeTypeName != null, "更新内容不能全为空");
    }
}
