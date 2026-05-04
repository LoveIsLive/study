package com.kwang.study.llm.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import java.io.InputStream;

@Slf4j
public class DocumentParserUtil {
    private static final Tika TIKA = new Tika();

    /**
     * 将 PDF, Word, Excel, PPT 等文件流解析为纯文本
     */
    public static String extractText(InputStream inputStream, String fileName) {
        try {
            // Tika 会自动识别格式并提取文本
            String text = TIKA.parseToString(inputStream);
            return String.format("【文件：%s 的内容如下】\n%s", fileName, text);
        } catch (Exception e) {
            log.error("文档解析失败: {}", fileName, e);
            throw new RuntimeException("文件解析失败: " + fileName);
        }
    }
}