package com.kwang.study.mathvision.workflow.util;

/**
 * Heuristic token estimator for MathVision prompt budget management.
 *
 * Provider tokenizers are model-specific. This estimator intentionally stays
 * conservative enough for trimming decisions without depending on a remote
 * count-tokens API.
 */
public final class TokenEstimator {

    private static final double ASCII_TEXT_TOKENS_PER_CHAR = 0.34D;
    private static final double WHITESPACE_TOKENS_PER_CHAR = 0.25D;
    private static final double ASCII_SYMBOL_TOKENS_PER_CHAR = 0.75D;
    private static final double CJK_TOKENS_PER_CHAR = 0.70D;
    private static final double NON_ASCII_TOKENS_PER_CHAR = 1.00D;

    private TokenEstimator() {
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        double tokens = 0.0D;
        int asciiTextRun = 0;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            if (isAsciiText(codePoint)) {
                asciiTextRun++;
            } else {
                tokens += asciiTextRunTokens(asciiTextRun);
                asciiTextRun = 0;
                tokens += tokenCost(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        tokens += asciiTextRunTokens(asciiTextRun);
        return (int) Math.ceil(tokens);
    }

    private static double asciiTextRunTokens(int length) {
        if (length <= 0) {
            return 0.0D;
        }
        return Math.max(1.0D, Math.ceil(length * ASCII_TEXT_TOKENS_PER_CHAR));
    }

    private static double tokenCost(int codePoint) {
        if (Character.isWhitespace(codePoint)) {
            return WHITESPACE_TOKENS_PER_CHAR;
        }
        if (codePoint > 0x7F) {
            return isCjkLike(codePoint) ? CJK_TOKENS_PER_CHAR : NON_ASCII_TOKENS_PER_CHAR;
        }
        return ASCII_SYMBOL_TOKENS_PER_CHAR;
    }

    private static boolean isAsciiText(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9')
                || codePoint == '_'
                || codePoint == '-';
    }

    private static boolean isCjkLike(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
