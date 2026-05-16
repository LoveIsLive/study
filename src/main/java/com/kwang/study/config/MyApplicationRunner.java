package com.kwang.study.config;

import com.kwang.study.auth.config.JWTConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * @author kwang
 * @date 2025/09/02
 */
@Component
public class MyApplicationRunner implements ApplicationRunner {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JWTConfig jwtConfig;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Environment environment = applicationContext.getEnvironment();
        System.out.println(environment);
        System.out.println(jwtConfig);

    }
}
