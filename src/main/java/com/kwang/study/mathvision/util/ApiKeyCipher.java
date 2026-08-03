package com.kwang.study.mathvision.util;

import com.kwang.study.mathvision.config.MathVisionModelCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Key 对称加解密 + 脱敏。
 * 算法: AES-256-GCM; 密钥由 Nacos dataId math-vision 的 apikey.secret 经 SHA-256 派生为 32 字节。
 * 存储格式: base64(iv(12 bytes) || ciphertext || tag)。
 */
@Component
public class ApiKeyCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec keySpec;

    @Autowired
    public ApiKeyCipher(MathVisionModelCatalog catalog) {
        this(requireSecret(catalog));
    }

    /** 仅供不启动 Spring/Nacos 的单元测试构造。 */
    public ApiKeyCipher(String secret) {
        this.keySpec = deriveKey(secret);
    }

    private static String requireSecret(MathVisionModelCatalog catalog) {
        String secret = catalog != null && catalog.getApikey() != null
                ? catalog.getApikey().getSecret()
                : null;
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException(
                    "Nacos 配置缺失: dataId=math-vision, key=apikey.secret");
        }
        return secret.trim();
    }

    private static SecretKeySpec deriveKey(String secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] key = sha.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化 API Key 加密密钥失败", e);
        }
    }

    /** 加密明文 API Key, 返回 base64 字符串。 */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    /** 解密 base64 密文, 返回明文 API Key。 */
    public String decrypt(String encrypted) {
        if (encrypted == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(encrypted);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            byte[] ct = new byte[all.length - IV_LENGTH];
            System.arraycopy(all, IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 解密失败", e);
        }
    }

    /** 脱敏展示: 保留前缀与后 4 位, 如 sk-****abcd。 */
    public String mask(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        String s = plain.trim();
        if (s.length() <= 8) {
            return "****";
        }
        String prefix = s.startsWith("sk-") ? "sk-" : s.substring(0, 2);
        String tail = s.substring(s.length() - 4);
        return prefix + "****" + tail;
    }
}
