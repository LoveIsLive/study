package com.kwang.study.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // favicon.ico
        registry.addViewController("/favicon.ico")
                .setViewName("forward:/static/favicon.ico");

        // 文件系统
        registry.addViewController("/fs/**")
                .setViewName("forward:/static/fs/fs.html");

        // 主页
        registry.addViewController("/")
                .setViewName("redirect:/fs");
    }
}
