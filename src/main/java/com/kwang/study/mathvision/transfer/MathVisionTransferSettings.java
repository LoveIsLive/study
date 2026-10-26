package com.kwang.study.mathvision.transfer;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.nio.file.Path;

@Component
public class MathVisionTransferSettings {
    private final MathVisionModelCatalog catalog;
    private final Environment environment;
    public MathVisionTransferSettings(MathVisionModelCatalog catalog, Environment environment) {
        this.catalog = catalog; this.environment = environment;
    }
    public MathVisionModelCatalog.DistributedCatalog config() { return catalog.getDistributed(); }
    public String secret() {
        String value = environment.getProperty("mathvision.service-secret", config().getServiceSecret());
        if (value == null || value.length() < 32) throw new IllegalStateException("Configure distributed.serviceSecret in Nacos math-vision (at least 32 characters)");
        return value;
    }
    public String masterUrl() {
        return environment.getProperty("mathvision.master-url", config().getMasterUrl()).replaceAll("/+$", "");
    }
    public Path root() {
        return Path.of(environment.getProperty("mathvision.transfer-root", config().getTransferRoot())).toAbsolutePath().normalize();
    }
}
