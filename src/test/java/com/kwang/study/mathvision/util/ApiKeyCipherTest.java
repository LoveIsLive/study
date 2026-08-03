package com.kwang.study.mathvision.util;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiKeyCipherTest {

    @Test
    void readsSecretFromMathVisionNacosCatalog() {
        MathVisionModelCatalog catalog = new MathVisionModelCatalog();
        catalog.getApikey().setSecret("test-secret-from-math-vision-nacos");

        ApiKeyCipher cipher = new ApiKeyCipher(catalog);
        String encrypted = cipher.encrypt("sk-example");

        assertEquals("sk-example", cipher.decrypt(encrypted));
    }

    @Test
    void rejectsMissingNacosSecret() {
        MathVisionModelCatalog catalog = new MathVisionModelCatalog();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new ApiKeyCipher(catalog));

        assertEquals("Nacos 配置缺失: dataId=math-vision, key=apikey.secret", error.getMessage());
    }
}
