package com.kwang.study.dto.fs.request;

import com.kwang.study.dto.BaseRequestDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
@EqualsAndHashCode(callSuper = true)
public class UploadChunkRequestDTO extends BaseRequestDTO {
    private static final long serialVersionUID = 6380087542162786332L;

    @NotNull(message = "文件id不能为空")
    private Long fileId;

    @NotNull(message = "块索引不能为空")
    @Min(value = 0, message = "块索引不能小于0")
    private Integer chunkIndex;

    @Min(value = 1, message = "总共快数不能小于1")
    @NotNull(message = "总共块数量不能为空")
    private Integer totalChunks;

    @NotNull(message = "文件块不能为空")
    private MultipartFile chunk;

    @Override
    public void check() {
        super.check();
        Assert.isTrue(chunkIndex < totalChunks, "块索引不能大于块数量");
    }
}
