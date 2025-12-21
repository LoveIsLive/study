package com.kwang.study.llm.controller;

import com.kwang.study.common.R;
import com.kwang.study.dto.FileItem;
import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.dto.result.FileObjectResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.homework.dto.result.DownloadDTO;
import com.kwang.study.llm.core.Tools;
import com.kwang.study.llm.dto.request.ChatRequestDTO;
import com.kwang.study.llm.dto.request.ContentPartMessage;
import com.kwang.study.llm.mapper.ChatMemoryMapper;
import com.kwang.study.llm.pojo.ChatMemory;
import com.kwang.study.llm.pojo.ChatSession;
import com.kwang.study.llm.service.LLMService;
import com.kwang.study.utils.DownloadUtils;
import com.openai.models.chat.completions.ChatCompletionMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.kwang.study.constant.ApiPrefixConstant.LLM_BASE_PREFIX;
import static com.kwang.study.constant.RedisKeyPrefixConstant.DOWNLOAD_ID_PREFIX;

@RestController
@RequestMapping(LLM_BASE_PREFIX) // /api/v1/llm
@RequiredArgsConstructor
public class LLMController {

    private final LLMService llmService;
    private final ChatMemoryMapper chatMemoryMapper;
    private final FileStorageService fileStorageService;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 1. 获取新的 Session ID
     */
    @GetMapping("/session/new")
    public ResponseEntity<R<String>> createSession() {
        return ResponseEntity.ok(R.success(llmService.createSessionId()));
    }

    /**
     * 2. 流式对话接口 (Stream Mode)
     * 适用于快速问答、普通聊天
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestPart ChatRequestDTO request,
                                 @RequestPart(value = "files", required = false) List<MultipartFile> smallFiles) throws Exception {

        processFile(smallFiles, request);
        return llmService.streamChat(request);
    }

    /**
     * 3. Agent 对话接口 (Agent Mode)
     * 适用于需要工具调用、复杂任务规划的场景
     */
    @PostMapping("/chat/agent")
    public ResponseEntity<R<ChatCompletionMessage>> agentChat(@Valid @RequestPart ChatRequestDTO request,
                                                              @RequestPart(value = "files", required = false) List<MultipartFile> smallFiles) throws Exception {
        processFile(smallFiles, request);

        ChatCompletionMessage result = llmService.agentChat(request);
        return ResponseEntity.ok(R.success(result));
    }

    /**
     * 获取历史记录
     */
    @GetMapping("/history/{sessionId}")
    public ResponseEntity<R<List<ChatMemory>>> getHistory(@PathVariable String sessionId) {
        return ResponseEntity.ok(R.success(chatMemoryMapper.findBySessionId(sessionId)));
    }

    @GetMapping("/sessions")
    public ResponseEntity<R<List<ChatSession>>> getSessions() {
        return ResponseEntity.ok(R.success(llmService.getUserSessions()));
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<R<Void>> deleteSession(@PathVariable String sessionId) {
        llmService.deleteSession(sessionId);
        return ResponseEntity.ok(R.success(null));
    }

    // 下载
    @GetMapping("/get/downloadId")
    public ResponseEntity<R<String>> produceDownloadUUID(@NotBlank @RequestParam("path") String path,
                                                         @NotBlank @RequestParam("fileName") String fileName) {
        String downloadId = UUID.randomUUID().toString();
        DownloadDTO dto = new DownloadDTO(path, fileName);
        redisTemplate.opsForValue().set(DOWNLOAD_ID_PREFIX + downloadId, dto, 30, TimeUnit.MINUTES);
        return ResponseEntity.ok(R.success(downloadId));
    }


    @GetMapping("/download")
    public void downloadFile(@NotBlank @RequestParam("path") String path,
                             @RequestParam(name = "mode", defaultValue = "attachment") String mode,
                             @NotBlank @RequestParam("token") String token,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        DownloadDTO dto = (DownloadDTO) redisTemplate.opsForValue().get(DOWNLOAD_ID_PREFIX + token);
        if (dto == null || !Objects.equals(path, dto.getActualPath())) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().print("没有权限");
            response.setContentType("text/plain; charset=UTF-8");
            return;
        }

        FileObjectResult fileObject = fileStorageService.getFileObject(path);
        fileObject.setName(dto.getFileName());
        DownloadUtils.downloadFile(fileObject, mode, request, response);
    }

    private void processFile(List<MultipartFile> smallFiles, ChatRequestDTO request) throws IOException {
        checkFileSize(smallFiles);

        if (!CollectionUtils.isEmpty(smallFiles)) {
            // 先存储文件
            ArrayList<FileItem> fileItems = new ArrayList<>();
            for (MultipartFile smallFile : smallFiles) {
                String fileName = smallFile.getOriginalFilename();
                String ext = fileName == null ? "" : fileName.substring(fileName.lastIndexOf('.'));
                String path = FileStorageModuleNameEnum.LLMCHAT_NAME.getModuleName()
                        + "/" + cn.hutool.core.lang.UUID.randomUUID().toString(true) + ext;
                try (InputStream inputStream = smallFile.getInputStream()) {
                    fileStorageService.createFile(path, inputStream, smallFile.getContentType());
                }

                FileObjectResult fileObject = fileStorageService.getFileObject(path);
                fileItems.add(FileItem.builder()
                        // original fileName
                        .fileName(fileName)
                        .mimeTypeName(fileObject.getMimeTypeName())
                        .fileSize(fileObject.getSize())
                        .path(path)
                        .stream(fileObject.getContent())
                        .build());
            }
            request.setContentPartMessage(ContentPartMessage.builder()
                    .text(request.getMessage())
                    .files(fileItems)
                    .build());
        }
    }

    private void checkFileSize(List<MultipartFile> files) {
        if (!CollectionUtils.isEmpty(files)) {
            long allSize = 0;
            for (MultipartFile file : files) {
                allSize += file.getSize();
                Assert.isTrue(allSize <= 7 * 1024 * 1024, "上传文件大于7MB");
            }
        }
    }
}