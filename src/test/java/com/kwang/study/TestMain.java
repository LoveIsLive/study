package com.kwang.study;

import cn.hutool.core.util.HexUtil;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.apache.commons.text.StringSubstitutor;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.crypto.SecretKey;
import java.io.IOException;
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
        Path path = Paths.get("a/b");
        Files.createDirectories(path);
    }
}
