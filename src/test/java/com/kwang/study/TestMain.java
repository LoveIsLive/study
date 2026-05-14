package com.kwang.study;

import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.ObjectUtil;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.kwang.study.homework.pojo.HomeworkSubmissionDetail;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.apache.commons.text.StringSubstitutor;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.MimeType;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import static com.kwang.study.llm.service.RAG.MIND_BLOCK_GEN_SYSTEM_PROMPT;

public class TestMain {
    public static void main(String[] args) throws Exception {
        // 1. 定义模版 (Java 11 只能用 + 号拼接换行，虽然丑但在 Java 11 没办法)
        String template = "你好，${name}\n" +
                "欢迎来到 ${place}。";

        // 2. 准备参数
        Map<String, String> values = Map.of(
                "name", "张三",
                "place", "Spring Boot 世界"
        );

        // 3. 执行替换
        StringSubstitutor sub = new StringSubstitutor(values);
        String result = sub.replace(template);

        System.out.println(result);
    }

    @Test
    public void f() throws Exception {
        File file = new File("C:\\Users\\30107\\Desktop\\答辩秘书线下工作细则Checklist1-苏.pdf");
        // AutoDetectParser 会自动识别文件类型并调用对应解析器
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1); // -1 表示无字符数限制
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        try (InputStream stream = new FileInputStream(file)) {
            parser.parse(stream, handler, metadata, context);
        }

        System.out.println("=== 文档内容 ===");
        System.out.println(handler.toString());

        System.out.println("=== 元数据 ===");
        for (String name : metadata.names()) {
            System.out.println(name + ": " + metadata.get(name));
        }
    }

    @Test
    public void f1() throws Exception {
        System.out.println(MIND_BLOCK_GEN_SYSTEM_PROMPT);
    }

}
