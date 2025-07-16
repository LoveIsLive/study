package com.kwang.study.controller;

import com.kwang.study.common.R;
import com.kwang.study.pojo.Node;
import com.kwang.study.service.ChunkService;
import com.kwang.study.service.NodeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

@RestController
@RequestMapping("/chunk")
@Validated
public class ChunkController {
    @Autowired
    private ChunkService chunkService;

    @PostMapping("/init")
    public ResponseEntity<R<Node>> initUploadBigFile(
            @RequestParam("name") @NotBlank String name,
            @RequestParam("parentId") Long parentId,
            @RequestParam(value = "permissions", required = false) String permissions) throws Exception {

        Node node = chunkService.initBigFileNode(name, parentId, permissions);
        return ResponseEntity.ok(R.success(node));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadChunk(
            @RequestParam("fileId") Long fileId,
            @RequestParam("chunkIndex") Integer chunkIndex,
            @RequestParam("totalChunks") Integer totalChunks,
            @RequestParam("chunk") MultipartFile chunk) throws Exception {
        chunkService.uploadChunk(fileId, chunkIndex, chunk.getInputStream());
        if (Objects.equals(chunkIndex, totalChunks)) {
            chunkService.mergeChunks(fileId);
            return ResponseEntity.ok("Upload complete");
        }
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/download/{id}")
    public void downloadFile(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        chunkService.downloadFile(id, request, response);
    }

}
