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

        // 课程仓库系统
        registry.addViewController("/ware/**")
                .setViewName("forward:/static/ware/ware.html");

        // 认证授权系统
        registry.addViewController("/auth/**")
                .setViewName("forward:/static/auth/login.html");

        // 主页
        registry.addViewController("/")
                .setViewName("redirect:/ware/home");
    }
}
