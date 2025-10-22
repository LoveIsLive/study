package com.kwang.study.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ContentVersionStrategy;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.VersionResourceResolver;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 1. 创建 VersionResourceResolver 以启用内容版本策略 (对应 YAML 中的 content strategy)
        VersionResourceResolver versionResolver = new VersionResourceResolver()
                .addVersionStrategy(new ContentVersionStrategy(), "/**"); // paths: /**

        registry.addResourceHandler("/**") // 匹配所有请求
                .addResourceLocations("classpath:/static/") // 资源位置

                // 2. 配置缓存策略 (对应 YAML 中的 cachecontrol)
                .setCacheControl(CacheControl.maxAge(3600, TimeUnit.SECONDS).cachePublic())

                // 3. 启用资源链 (对应 YAML 中的 chain.enabled)
                //    并添加我们自定义的解析器链
                .resourceChain(true)
                .addResolver(versionResolver) // 添加版本解析器
                .addResolver(new PathResourceResolver() { // 添加我们的SPA路由回退解析器
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requestedResource = location.createRelative(resourcePath);

                        // 如果请求的资源存在，则正常返回
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        }

                        // 排除所有API和WebSocket的路径，让它们被404或由其他Controller处理
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("ws/")) {
                            return null;
                        }

                        // 对于其他所有找不到的路径（这些就是前端路由），都返回index.html
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}
