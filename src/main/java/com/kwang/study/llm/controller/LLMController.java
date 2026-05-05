package com.kwang.study.llm.controller;

import cn.hutool.core.io.unit.DataSizeUtil;
import com.kwang.study.common.R;
import com.kwang.study.dto.FileItem;
import com.kwang.study.enums.FileStorageModuleNameEnum;
import com.kwang.study.fs.dto.result.FileObjectResult;
import com.kwang.study.fs.mapper.NodeMapper;
import com.kwang.study.fs.pojo.Node;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.homework.dto.result.DownloadDTO;
import com.kwang.study.llm.core.LLM;
import com.kwang.study.llm.core.Prompt;
import com.kwang.study.llm.core.Tools;
import com.kwang.study.llm.dto.request.AIFileSummaryDTO;
import com.kwang.study.llm.dto.request.ChatRequestDTO;
import com.kwang.study.llm.dto.request.ContentPartMessage;
import com.kwang.study.llm.dto.request.FileNameAndPath;
import com.kwang.study.llm.dto.response.MindGenResponseDTO;
import com.kwang.study.llm.mapper.ChatMemoryMapper;
import com.kwang.study.llm.pojo.ChatMemory;
import com.kwang.study.llm.pojo.ChatSession;
import com.kwang.study.llm.service.LLMService;
import com.kwang.study.utils.DownloadUtils;
import com.openai.models.chat.completions.ChatCompletionMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.kwang.study.constant.ApiPrefixConstant.LLM_BASE_PREFIX;
import static com.kwang.study.constant.RedisKeyPrefixConstant.DOWNLOAD_ID_PREFIX;

@Slf4j
@RestController
@RequestMapping(LLM_BASE_PREFIX) // /api/v1/llm
@RequiredArgsConstructor
public class LLMController {

    private final LLMService llmService;
    private final ChatMemoryMapper chatMemoryMapper;
    private final FileStorageService fileStorageService;
    private final LLMFileUploadController llmFileUploadController;

    /**
     * 1. 获取新的 Session ID
     */
    @GetMapping("/session/new")
    public ResponseEntity<R<String>> createSession(@RequestParam(name = "purpose", defaultValue = "chat_window") String purpose) {
        return ResponseEntity.ok(R.success(llmService.createSessionId(purpose)));
    }

    /**
     * 2. 流式对话接口 (Stream Mode)
     * 适用于快速问答、普通聊天
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestPart ChatRequestDTO request,
                                 @RequestPart(value = "files", required = false) List<MultipartFile> smallFiles) throws Exception {
        request.setType("stream");
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
        request.setType("agent");
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
    public ResponseEntity<R<List<ChatSession>>> getSessions(@RequestParam(name = "purpose",
            defaultValue = "chat_window") String purpose) {
        return ResponseEntity.ok(R.success(llmService.getUserSessions(purpose)));
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<R<Void>> deleteSession(@PathVariable String sessionId) {
        llmService.deleteSession(sessionId);
        return ResponseEntity.ok(R.success(null));
    }

    @PostMapping("/chat/mind")
    public ResponseEntity<R<MindGenResponseDTO>> mindChat(@Valid @RequestPart ChatRequestDTO request,
                                                          @RequestPart(value = "files", required = false) List<MultipartFile> smallFiles) throws Exception {
        request.setType("chat");
        request.setScene("mind-block-gen"); // 强制场景为 mind
        processFile(smallFiles, request);

        MindGenResponseDTO result = llmService.mindChat(request);
        return ResponseEntity.ok(R.success(result));
    }


    @GetMapping("/getFile")
    public ResponseEntity<StreamingResponseBody> getFile(@NotBlank @RequestParam("path") String path) throws IOException {
        // 简单获取文件，没有做权限校验
        FileObjectResult fileObject = fileStorageService.getFileObject(path);

        if (fileObject == null || fileObject.getContent() == null) {
            return ResponseEntity.notFound().build();
        }

        // 定义流式返回的 body
        StreamingResponseBody responseBody = outputStream -> {
            // 在实际发生数据写入时，通过 try-with-resources 安全关闭 InputStream
            try (InputStream inputStream = fileObject.getContent()) {
                StreamUtils.copy(inputStream, outputStream);
            } catch (IOException e) {
                // 忽略或记录客户端断开异常
            }
        };

        // 解析 MediaType 并提供兜底方案（防止 MediaType.valueOf 抛出 InvalidMediaTypeException 导致流未消费）
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(fileObject.getMimeTypeName());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(fileObject.getSize())
                .body(responseBody);
    }

    @PostMapping("/ai/summary")
    public ResponseEntity<R<Void>> asyncAIFileSummary(@RequestBody @Valid AIFileSummaryDTO request) {
        llmService.asyncAIFileSummary(request);
        return ResponseEntity.ok(R.success(null));
    }

    private void processFile(List<MultipartFile> smallFiles, ChatRequestDTO request) throws IOException {
        checkFileSize(smallFiles);

        if (!CollectionUtils.isEmpty(smallFiles) || !CollectionUtils.isEmpty(request.getUploadFiles())) {
            ArrayList<FileItem> fileItems = new ArrayList<>();
            // 先处理小文件
            if (!CollectionUtils.isEmpty(smallFiles)) {
                for (MultipartFile smallFile : smallFiles) {
                    String fileName = smallFile.getOriginalFilename();
                    String path = llmFileUploadController.produceFilePath(fileName);
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
                log.info("上传图片, {}", fileItems);
            }
            // 再处理大文件
            if (!CollectionUtils.isEmpty(request.getUploadFiles())) {
                for (FileNameAndPath fileNameAndPath : request.getUploadFiles()) {
                    FileObjectResult fileObject = fileStorageService.getFileObject(fileNameAndPath.getFilePath());
                    fileItems.add(FileItem.builder()
                            // original fileName
                            .fileName(fileNameAndPath.getFileName())
                            .mimeTypeName(fileObject.getMimeTypeName())
                            .fileSize(fileObject.getSize())
                            .path(fileNameAndPath.getFilePath())
                            .stream(fileObject.getContent())
                            .build());
                }
            }

            request.setContentPartMessage(ContentPartMessage.builder()
                    .text(request.getMessage())
                    .files(fileItems)
                    .build());
        }
    }

    // 可以不要，Spring已经提供了
    private void checkFileSize(List<MultipartFile> files) {
        if (!CollectionUtils.isEmpty(files)) {
            long allSize = 0;
            for (MultipartFile file : files) {
                allSize += file.getSize();
                Assert.isTrue(allSize <= DataSizeUtil.parse("100MB"), "上传文件超过100MB");
            }
        }
    }
}