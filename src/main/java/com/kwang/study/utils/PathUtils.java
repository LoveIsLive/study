package com.kwang.study.utils;

import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PathUtils {

    // 基础非法字符正则（不包含斜杠，因为路径拆分时会用到）
    private static final Pattern FORBIDDEN_CHARS_PATTERN =
            Pattern.compile("[<>&|;`$\"'*?#~!:]");

    /**
     * 判断一个字符串是否是合法的Unix路径，遵循以下严格规则：
     * 1. 不允许出现 shell 特殊字符。
     * 2. 不允许路径项为 "." 或 "..".
     * 3. 不允许路径项为空 (即不允许出现 "//").
     * 4. 路径不能以 "/" 结尾（根目录 "/" 除外）.
     *
     * @param path 要校验的路径字符串
     * @return 如果路径合法则返回 true，否则返回 false
     */
    public static boolean isValidPath(String path) {
        // 1. 处理null和空字符串
        if (path == null || path.isEmpty()) {
            return false;
        }

        // 2. 根目录 "/" 是唯一允许以斜杠结尾的合法路径
        if (path.equals("/")) {
            return true;
        }

        // 3. 路径项不能为空，不能包含连续斜杠 "//"
        if (path.contains("//")) {
            return false;
        }

        // 4. 路径不能以 "/" 结尾 (根目录已在前面处理)
        if (path.endsWith("/")) {
            return false;
        }

        // 5. 必须是绝对路径 (你的系统似乎要求所有路径以 "/" 开头)
        if (!path.startsWith("/")) {
            return false;
        }

        // 6. 将路径按 "/" 分割成各个部分进行校验
        String[] components = path.split("/");

        // 因为路径以 "/" 开头，components[0] 是空字符串，从索引 1 开始遍历
        for (int i = 1; i < components.length; i++) {
            // ★ 核心优化：路径里的每一段，本质上就是一个 Name，直接复用 isValidName
            if (!isValidName(components[i])) {
                return false;
            }
        }

        return true;
    }

    /**
     * 判断是否为普通的合法路径 (是有效路径且不是根目录)
     */
    public static boolean isOrdinaryPath(String path) {
        return isValidPath(path) && !"/".equals(path);
    }

    /**
     * 判断文件或目录的名称是否合法（用于重命名、创建文件/目录时的名称校验）
     */
    public static boolean isValidName(String name) {
        // 1. 判空
        if (!StringUtils.hasLength(name)) {
            return false;
        }

        // 2. ★ 致命Bug修复：名称中绝对不能包含 正斜杠 '/' 和 反斜杠 '\'
        if (name.contains("/") || name.contains("\\")) {
            return false;
        }

        // 3. ★ Bug修复：去除首尾空格后，不能是相对路径符号 "." 或 ".."
        String trimmedName = name.trim();
        if (trimmedName.equals(".") || trimmedName.equals("..")) {
            return false;
        }

        // 4. 不能包含系统禁用的 shell 特殊字符
        Matcher matcher = FORBIDDEN_CHARS_PATTERN.matcher(name);
        return !matcher.find();
    }
}