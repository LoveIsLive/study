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
public class InitUploadBigFileRequestDTO extends BaseRequestDTO {
    private static final long serialVersionUID = 7839290450542888451L;

    private String path;

    private String mimeTypeName;

    @Override
    public void check() {
        super.check();
        Assert.isTrue(PathUtils.isOrdinaryPath(path), "路径非法:" + path);
        Assert.notNull(mimeTypeName, "mimeTypeName不可为空");
    }
}
