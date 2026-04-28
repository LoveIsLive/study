package com.kwang.study.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ContentVersionStrategy;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.VersionResourceResolver;

import java.io.IOException;
import java.util.concurrent.ThreadPoolExecutor;
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

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // 1. 设置异步请求超时时间（单位毫秒）
        // 生产环境建议设置，例如 60 秒。防止网络极差的用户一直占用服务器连接
        configurer.setDefaultTimeout(60000L);

        // 2. 配置专属的异步线程池
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 核心线程数：根据你的服务器 CPU 核数和图片请求并发量来定（例如 IO 密集型可设为 CPU核数 * 2）
        executor.setCorePoolSize(2);

        // 最大线程数：当核心线程都在忙，且队列满了时，最多能开多少个线程
        executor.setMaxPoolSize(10);

        // 队列容量：用来缓冲瞬时突发的高并发请求
        executor.setQueueCapacity(20);

        // 线程前缀名：强烈建议设置！当线上出现问题看日志或 jstack 时，能一眼认出这是用来下载图片的线程
        executor.setThreadNamePrefix("img-async-");

        // ================= 生产环境关键配置 =================

        // 3. 拒绝策略（重要）：当最大线程数满了，队列也满了，新来的请求怎么办？
        // CallerRunsPolicy 表示：把这个任务交回给调用方（即 Tomcat 的 NIO 线程）去同步执行。
        // 这样既不会抛出异常导致用户图片加载失败，又能起到天然的限流作用。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 4. 优雅停机（重要）：当运维发版重启服务时，让正在下载图片的线程把图片传完再关闭，而不是直接杀掉
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30); // 最多等 30 秒

        // =================================================

        executor.initialize();
        configurer.setTaskExecutor(executor);
    }
}
