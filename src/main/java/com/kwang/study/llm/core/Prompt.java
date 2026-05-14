package com.kwang.study.llm.core;

import cn.hutool.core.io.unit.DataSizeUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwang.study.dto.FileItem;
import com.kwang.study.fs.dto.result.FileObjectResult;
import com.kwang.study.fs.service.FileStorageService;
import com.kwang.study.llm.dto.request.ContentPartMessage;
import com.kwang.study.llm.pojo.ChatMemory;
import com.kwang.study.llm.util.DocumentParserUtil;
import com.kwang.study.llm.util.OCR;
import com.kwang.study.utils.SpringContextUtil;
import com.kwang.study.utils.StreamUtil;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.kwang.study.constant.ApiPrefixConstant.LLM_BASE_PREFIX;

// 负责 user, ai, tool的消息
@Slf4j
public class Prompt {

    // Getter 供 LLM 调用时获取底层 Message 列表
    @Getter
    private final List<ChatCompletionMessageParam> messages = new ArrayList<>();

    private Prompt() {}

    public static Prompt create() {
        return new Prompt();
    }

    /**
     * 批量加载历史记录 (从数据库 Entity 转换)
     */
    public Prompt addHistory(List<ChatMemory> memories, FileStorageService fileStorageService, ObjectMapper objectMapper) {
        if (memories == null) return this;
        for (ChatMemory mem : memories) {
            if ("user".equals(mem.getRole())) {
                if ("text".equals(mem.getType())) {
                    this.addUser(mem.getContent());
                } else if ("file".equals(mem.getType())) {
                    try {
                        ContentPartMessage message = objectMapper.readValue(mem.getContent(), ContentPartMessage.class);
                        this.addContentPartMessageMessageUser(ContentPartMessage.builder()
                                .text(message.getText())
                                .files(message.getFiles().stream()
                                        .peek(item -> {
                                            try {
                                                FileObjectResult fileObject = fileStorageService.getFileObject(item.getPath());
                                                item.setStream(fileObject.getContent());
                                            } catch (IOException e) {
                                                throw new RuntimeException(e);
                                            }
                                        }).collect(Collectors.toList()))
                                .build());
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    throw new IllegalArgumentException("chat memory user type非text和file");
                }
            } else if ("assistant".equals(mem.getRole())) {
                if ("text".equals(mem.getType())) {
                    this.addAssistant(mem.getContent());
                } else if ("mind_result".equals(mem.getType())) {
                    this.addAssistant(mem.getContent());
                } else if ("tool".equals(mem.getType())) {
                    try {
                        ChatCompletionMessage message = objectMapper.readValue(mem.getContent(), ChatCompletionMessage.class);
                        this.addAssistant(message);
                        // An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'.
                        message.toolCalls().ifPresent(chatCompletionMessageToolCalls -> chatCompletionMessageToolCalls
                                .forEach(tool -> {
                                    this.addToolMessage(tool.asFunction().id(), "Success");
                                }));
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    throw new IllegalArgumentException("chat memory assistant type非text和tool");
                }
            }
        }
        return this;
    }

    public Prompt addUser(String content) {
        messages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder()
                .content(content).build()));
        return this;
    }

    /**
     * 添加ContentPartMessage
     */
    public Prompt addContentPartMessageMessageUser(ContentPartMessage contentPartMessage) {
        if (contentPartMessage == null
                || CollectionUtils.isEmpty(contentPartMessage.getFiles()))
            return this;
        ArrayList<ChatCompletionContentPart> parts = new ArrayList<>();

        if (contentPartMessage.getText() != null) {
            ChatCompletionContentPart questionContentPart =
                    ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder()
                            .text(contentPartMessage.getText())
                            .build());
            parts.add(questionContentPart);
        }

        List<FileItem> files = contentPartMessage.getFiles();
        files.forEach(file -> {
            if (file.getMimeTypeName() == null) {
                throw new IllegalArgumentException("文件非法: 存在 ContentType 为空的文件 -> " + file.getFileName());
            }
        });

        files.stream()
                .map(file -> {
                    if (file.getMimeTypeName().startsWith("image/")) {
                        String imageUrl = null;
                        if (file.getFileSize() < DataSizeUtil.parse("7MB")) {
                            imageUrl = OCR.base64Encoder(file);
                        } else {
                            int port = SpringContextUtil.getPort();
                            imageUrl = "http://47.121.116.149:" + port + file.getPath();
                        }
                        return ChatCompletionContentPart.ofImageUrl(ChatCompletionContentPartImage.builder()
                                .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                                        .url(imageUrl)
                                        .build())
                                .build());
                    } else if (file.getMimeTypeName().startsWith("text/")) {
                        if (file.getFileSize() > DataSizeUtil.parse("7MB")) {
                            throw new IllegalArgumentException("文本文件太大，超过7MB");
                        }
                        try {
                            byte[] bytes = StreamUtil.readExactly(file.getStream(), Math.toIntExact(file.getFileSize()));
                            return ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder()
                                    .text(new String(bytes))
                                    .build());
                        } catch (IOException e) {
                            log.warn("读取文件失败: {}, 异常信息", file.getFileName(), e);
                            return ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder()
                                    .text("未找到文件：" + file.getFileName())
                                    .build());
                        }
                    } else if (file.getMimeTypeName().startsWith("video/")) {
                        // 视频处理
                        String videoUrl = null;
                        if (file.getFileSize() < DataSizeUtil.parse("7MB")) {
                            videoUrl = OCR.base64Encoder(file);
                        } else {
                            int port = SpringContextUtil.getPort();
                            videoUrl = "http://47.121.116.149:" + port +
                                    LLM_BASE_PREFIX + "/getFile?path=" + file.getPath();
                        }
                        ChatCompletionContentPartText videoPart = ChatCompletionContentPartText.builder()
                                .text("") // 填入空字符串绕过 SDK 原生非空校验，模型会忽略这个空文本
                                .putAdditionalProperty("type", JsonValue.from("video_url")) // 强行注入 type
                                .putAdditionalProperty("video_url", JsonValue.from(Map.of("url", videoUrl))) // 注入 video_url 节点
                                .build();
                        return ChatCompletionContentPart.ofText(videoPart);
                    } else {
                        if (file.getFileSize() > DataSizeUtil.parse("7MB")) {
                            throw new IllegalArgumentException("文件太大，超过7MB");
                        }
                        // taki兜底
                        String extractedText = DocumentParserUtil.extractText(file.getStream(), file.getFileName());
                        return ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder()
                                .text(extractedText)
                                .build());
                    }
                }).forEach(parts::add);

        messages.add(ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                        .content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(parts))
                        .build()));
        return this;
    }

    public Prompt addAssistant(String content) {
        messages.add(ChatCompletionMessageParam.ofAssistant(ChatCompletionAssistantMessageParam.builder()
                .content(content).build()));
        return this;
    }

    public Prompt addToolMessage(String toolCallId, Object content) {
        messages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                .toolCallId(toolCallId)
                .contentAsJson(content)
                .build()));
        return this;
    }

    public Prompt addAssistant(ChatCompletionMessage message) {
        messages.add(ChatCompletionMessageParam.ofAssistant(message.toParam()));
        return this;
    }
}