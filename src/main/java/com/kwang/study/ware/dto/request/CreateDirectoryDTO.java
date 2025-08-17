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
public class CreateDirectoryDTO extends BaseRequestDTO {
    private static final long serialVersionUID = -8646614385703462086L;

    private String path;

    @Override
    public void check() {
        super.check();
        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法:" + path);
    }
}
