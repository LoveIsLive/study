package com.kwang.study.utils;

import java.util.Set;

public class TextMimeUtil {
    private static final Set<String> TEXT_BASED_MIME_TYPES = Set.of(
            "text/plain",
            "text/html",
            "text/css",
            "text/javascript",
            "text/markdown",
            "text/xml",
            "application/json",
            "application/xml",
            "application/javascript",
            "image/svg+xml" // SVG本质上是XML，也是文本
    );

    // 辅助方法，用于判断MIME类型是否为文本类型
    public static boolean isTextBased(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        // 主要通过 startsWith('text/') 来捕获绝大多数情况
        if (mimeType.startsWith("text/")) {
            return true;
        }
        // 再通过集合检查其他应用类型的文本格式
        return TEXT_BASED_MIME_TYPES.contains(mimeType);
    }
}
