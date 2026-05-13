package com.kwang.study.llm.util;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;

@Slf4j
public class DocumentParserUtil {
    private static final Tika TIKA = new Tika();

    /**
     * 将 PDF, Word, Excel, PPT, MD, TXT 等文件流解析为纯文本
     */
    public static String extractText(InputStream inputStream, String fileName) {
        try {
            // 1. 创建 Metadata 对象
            Metadata metadata = new Metadata();
            // 2. 将文件名设置进去，这对于 .txt, .md, .csv 等没有魔数的文件至关重要
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);

            // 3. 将 stream 和 metadata 一起传给 Tika
            String text = TIKA.parseToString(inputStream, metadata);

            return String.format("【文件：%s 的内容如下】\n%s", fileName, text);
        } catch (Exception e) {
            log.error("文档解析失败: {}", fileName, e);
            throw new RuntimeException("文件解析失败: " + fileName);
        }
    }
}