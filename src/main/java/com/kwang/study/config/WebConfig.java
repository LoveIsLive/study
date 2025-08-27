package com.kwang.study.config;

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

        // 课程仓库模块
        registry.addViewController("/ware/**")
                .setViewName("forward:/static/ware/ware.html");

        // 认证授权模块
        registry.addViewController("/auth/**")
                .setViewName("forward:/static/auth/login.html");

        // 作业区模块
        registry.addViewController("/homework/**")
                .setViewName("forward:/static/homework/homework.html");

        // 主页
        registry.addViewController("/")
                .setViewName("redirect:/ware/home");
    }
}
