package com.kwang.study.ware.dto.request;

import com.kwang.study.dto.BaseRequestDTO;
import com.kwang.study.utils.PathUtils;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.util.Assert;

@Data
@EqualsAndHashCode(callSuper = true)
public class ArchiveRequestDTO extends BaseRequestDTO {
    private static final long serialVersionUID = -6873219512541829102L;

    private String sourceDirPath; // 需要打包的目录路径
    private String zipFileName;   // 生成的压缩包名称 (如: 资料.zip)

    @Override
    public void check() {
        super.check();
        Assert.isTrue(PathUtils.isOrdinaryPath(sourceDirPath), "源目录路径非法");
        Assert.isTrue(PathUtils.isValidName(zipFileName) && zipFileName.endsWith(".zip"), "压缩包名称必须合法且以 .zip 结尾");
    }
}