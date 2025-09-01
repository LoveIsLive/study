package com.kwang.study;

import cn.hutool.core.util.HexUtil;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;

public class TestMain {
    public static void main(String[] args) throws Exception {
        System.setProperty("logging.level.com.alibaba.nacos", "trace");

        String serverAddr = "47.121.116.149:8899";
        String dataId = "study";
        String group = "DEFAULT_GROUP";
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, serverAddr);
        properties.put(PropertyKeyConst.USERNAME, "nacos");
        properties.put(PropertyKeyConst.PASSWORD, "kwangnacos123");
        ConfigService configService = NacosFactory.createConfigService(properties);
        String content = configService.getConfig(dataId, group, 2000);
        System.out.println(content);
    }

    @Test
    public void f() {
        // 1. 创建 HttpClient 实例
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 2. 构建请求的 URI
        String uriString = "http://47.121.116.149:8899/nacos/v1/cs/configs?show=all&dataId=study&group=DEFAULT_GROUP&tenant=&namespaceId=";

        // 3. 创建 HttpRequest 实例，并设置所有请求头
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uriString))
                .GET() // 设置请求方法为 GET
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("AccessToken", "eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJuYWNvcyIsImV4cCI6MTc1Njc0NDM4OH0.1KLDqKvYcvdQn2iBaRBQk0L4JyDh0tjbxDwxvul7yyCIsUEAPqLV2PFeatkJ9U7X")
                .header("Authorization", "{\"accessToken\":\"eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJuYWNvcyIsImV4cCI6MTc1Njc0NDM4OH0.1KLDqKvYcvdQn2iBaRBQk0L4JyDh0tjbxDwxvul7yyCIsUEAPqLV2PFeatkJ9U7X\",\"tokenTtl\":18000,\"globalAdmin\":true,\"username\":\"nacos\"}")
                .header("Referer", "http://47.121.116.149:8899/nacos/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
                .header("X-Requested-With", "XMLHttpRequest")
                .build();

        try {
            // 4. 发送请求并获取响应
            // 使用 BodyHandlers.ofString() 来将响应体作为字符串处理
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // 5. 处理响应
            System.out.println("Status Code: " + response.statusCode());
            System.out.println("Response Body: " + response.body());

        } catch (IOException | InterruptedException e) {
            System.err.println("请求发送失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
