package com.kwang.study.mathvision.workflow.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

/** Shared endpoint construction for the worker's network checks and actual AI requests. */
public final class MathVisionModelEndpoint {
    private MathVisionModelEndpoint() { }

    public static HttpClient httpClient(int connectTimeoutMillis) {
        // Both clients use the JVM's proxy selector and TLS trust configuration.
        return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMillis)))
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    public static String baseUrl(String provider, String configured) {
        if (configured != null && !configured.isBlank()) return trim(configured.trim());
        switch (provider == null ? "" : provider.toLowerCase(Locale.ROOT)) {
            case "openai": return "https://api.openai.com/v1";
            case "moonshot": return "https://api.moonshot.cn/v1";
            case "zhipu": return "https://open.bigmodel.cn/api/paas/v4";
            case "google": return "https://generativelanguage.googleapis.com/v1beta";
            case "anthropic": return "https://api.anthropic.com/v1";
            default: throw new IllegalArgumentException("Missing model provider baseUrl: " + provider);
        }
    }

    /** No credential is included, including Google's API-key query parameter. */
    public static URI generationUri(String protocol, String baseUrl, String model) {
        String base = trim(baseUrl);
        String url;
        switch (protocol.toLowerCase(Locale.ROOT)) {
            case "google":
                url = base + (base.endsWith("/models") ? "/" : "/models/") + model + ":generateContent";
                break;
            case "anthropic": url = base.endsWith("/messages") ? base : base + "/messages"; break;
            default: url = base + "/chat/completions";
        }
        URI uri = URI.create(url);
        if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Model baseUrl must be an HTTP(S) address without credentials, query or fragment");
        }
        return uri.normalize();
    }

    static String logAddress(URI uri) {
        // Do not print arbitrary paths: custom gateways can embed credentials in them.
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }

    private static String trim(String value) { return value == null ? "" : value.replaceAll("/+$", ""); }
}
