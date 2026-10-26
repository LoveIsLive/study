package com.kwang.study.mathvision.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class MathVisionContentTag {
    private MathVisionContentTag() { }
    public static String of(String content) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return com.kwang.study.mathvision.transfer.MathVisionTransferClient.hex(bytes);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
