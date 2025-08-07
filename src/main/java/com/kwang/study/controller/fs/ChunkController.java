package com.kwang.study.controller.fs;

import cn.hutool.core.util.ObjectUtil;
import com.kwang.study.common.R;
import com.kwang.study.dto.fs.request.InitUploadBigFileRequestDTO;
import com.kwang.study.dto.fs.request.UploadChunkRequestDTO;
import com.kwang.study.dto.fs.result.UploadChunkResponseDTO;
import com.kwang.study.pojo.fs.Node;
import com.kwang.study.service.fs.ChunkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

import static com.kwang.study.constant.ApiPrefixConstant.FS_BASE_PREFIX;

@RestController
@RequestMapping(FS_BASE_PREFIX + "/chunk")
@Validated
@Slf4j
@PreAuthorize("hasRole('ROLE_TEACHER')")
public class ChunkController {
    @Autowired
    private ChunkService chunkService;

    // 使用简单的本地锁来防止并发合并问题
    private final ConcurrentHashMap<Long, Object> mapLock = new ConcurrentHashMap<>();

    @PostMapping("/init")
    public ResponseEntity<R<Node>> initUploadBigFile(@Valid @RequestBody InitUploadBigFileRequestDTO requestDTO) {
        requestDTO.check();

        Node node = chunkService.initBigFileNode(requestDTO.getName(), requestDTO.getParentId(),
                requestDTO.getPermissions(), requestDTO.getMimeTypeName());
        return ResponseEntity.ok(R.success(node, "大文件初始化成功"));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadChunk(@Valid @RequestBody UploadChunkRequestDTO requestDTO) throws Exception {
        requestDTO.check();

        UploadChunkResponseDTO merge = chunkService.uploadChunkAndMerge(requestDTO.getFileId(), requestDTO.getChunkIndex(),
                requestDTO.getTotalChunks(), requestDTO.getChunk().getInputStream());
        if (Boolean.TRUE.equals(merge.getMerged())) {
            return ResponseEntity.ok(R.success("上传完成，分片已完全合并"));
        } else if (Boolean.FALSE.equals(merge.getMerged())) {
            String progress = String.format("Chunk %d/%d uploaded.",
                    merge.getUploadNum() == null ? 0 : merge.getUploadNum(), requestDTO.getTotalChunks());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(R.success(progress, "分片上传成功"));
        } else if (Boolean.TRUE.equals(merge.getSuccess())){
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(R.success("正在合并"));
        } else {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(R.error(merge.getErrorMessage()));
        }
    }

}
