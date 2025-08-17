package com.kwang.study.utils;

import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PathUtils {
    private static final Pattern FORBIDDEN_CHARS_PATTERN =
            Pattern.compile("[<>&|;`$\"'*?#~!:]");

    // 需要是合法的unix路径
    /**
     * 判断一个字符串是否是合法的Unix路径，遵循以下严格规则：
     * 1. 不允许出现 shell 特殊字符。
     * 2. 不允许路径项为 "." 或 "..".
     * 3. 不允许路径项为空 (即不允许出现 "//").
     * 4. 路径不能以 "/" 结尾（根目录 "/" 除外）.
     * @param path 要校验的路径字符串
     * @return 如果路径合法则返回 true，否则返回 false
     */
    public static boolean isValidPath(String path) {
        // 规则：处理null和空字符串
        if (path == null || path.isEmpty()) {
            return false;
        }

        // 规则：根目录 "/" 是唯一允许以斜杠结尾的合法路径
        if (path.equals("/")) {
            return true;
        }

        // 规则：路径项不能为空，所以不能包含 "//"
        if (path.contains("//")) {
            return false;
        }

        // 规则：路径不能以 "/" 结尾 (根目录已在前面处理)
        if (path.endsWith("/")) {
            return false;
        }

        // 将路径按 "/" 分割成各个部分
        String[] components = path.split("/");

        for (int i = 1; i < components.length; i++) {
            String component = components[i];
            // 规则：不允许路径项为 "." 或 ".."
            String trimComponent = component.trim();
            if (trimComponent.equals(".") || trimComponent.equals("..")) {
                return false;
            }

            // 规则：不允许路径项包含应避免的特殊字符
            Matcher matcher = FORBIDDEN_CHARS_PATTERN.matcher(component);
            if (matcher.find()) {
                return false;
            }
        }
        // 所有检查都通过
        return true;
    }

    public static boolean isOrdinaryPath(String path) {
        // 是有效的path并且不是根目录
        return isValidPath(path) && !"/".equals(path);
    }

    public static boolean isValidName(String name) {
        if (!StringUtils.hasLength(name))
            return false;
        String trimName = name;
        if (trimName.equals(".") || trimName.equals(".."))
            return false;
        return !FORBIDDEN_CHARS_PATTERN.matcher(name).find();
    }
}
