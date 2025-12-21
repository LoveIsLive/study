package com.kwang.study.llm.util;

import com.kwang.study.dto.FileItem;
import com.kwang.study.llm.config.LLMGlobalConfig;
import com.kwang.study.llm.core.LLM;
import com.kwang.study.llm.core.LLMContext;
import com.kwang.study.llm.core.Prompt;
import com.kwang.study.llm.dto.request.ContentPartMessage;
import com.kwang.study.utils.StreamUtil;
import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
public class OCR {
    @Autowired
    private LLMGlobalConfig llmGlobalConfig;

    /**
     * 输入多个图像，给出文本提取结果。注意返回的是单个str
     */
    public String ocr(ContentPartMessage partMessage) {
        String scene = "sys-ocr";
        LLMGlobalConfig.SceneConfig sceneConfig = llmGlobalConfig.getScenes().getOrDefault(scene,
                llmGlobalConfig.getScenes().get("default"));

        LLMContext context = LLMContext.builder()
                .scene(scene)
                .llmConfig(sceneConfig)
                .build();
        LLM llm = LLM.create(context);

        Prompt prompt = Prompt.create()
                .addContentPartMessageMessageUser(partMessage);

        return llm.noStream(prompt, context);
    }

    public static String base64Encoder(FileItem file) {
        try {
            String contentType = file.getMimeTypeName(); // 上一步已校验非空
            byte[] bytes = StreamUtil.readExactly(file.getStream(), Math.toIntExact(file.getFileSize()));
            String base64Content = Base64.getEncoder().encodeToString(bytes);

            // 拼接标准格式: data:image/jpeg;base64,......
            return "data:" + contentType + ";base64," + base64Content;

        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + file.getFileName(), e);
        }
    }
}
