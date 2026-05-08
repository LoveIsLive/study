package com.kwang.study.ware.dto.request;

import com.kwang.study.dto.BaseRequestDTO;
import com.kwang.study.utils.PathUtils;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.util.Assert;

@Data
@EqualsAndHashCode(callSuper = true)
public class UnarchiveRequestDTO extends BaseRequestDTO {
    private String zipFilePath;   // 压缩包文件路径
    private String targetDirPath; // 解压到的目标目录路径

    @Override
    public void check() {
        super.check();
        Assert.isTrue(PathUtils.isOrdinaryPath(zipFilePath), "压缩包路径非法");
        Assert.isTrue(PathUtils.isValidPath(targetDirPath), "目标目录路径非法");
    }
}
