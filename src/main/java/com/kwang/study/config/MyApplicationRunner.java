package com.kwang.study.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.annotation.NacosValue;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * @author kwang
 * @date 2025/09/01
 */
@Component
public class MyApplicationRunner implements ApplicationRunner {
    private ConfigService configService;

    @PostConstruct
    public void init() {
        try {
            Properties properties = new Properties();
            properties.put("serverAddr", "47.121.116.149:8848");
            properties.put("username", "nacos");
            properties.put("password", "CQge7mqAUFF9Cmlsjz5Eto0LJ4Fpy4iDCZXCPS5yRkBRHExe0wdxr7xruhNhi0R");

            configService = NacosFactory.createConfigService(properties);
        } catch (NacosException e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取配置
     */
    public String getConfig(String dataId, String group) {
        try {
            return configService.getConfig(dataId, group, 3000);
        } catch (NacosException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {

    }
}
