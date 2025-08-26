package com.kwang.study.utils;

import com.kwang.study.fs.dto.result.FileObjectResult;
import com.kwang.study.fs.util.TextMimeUtil;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_PARTIAL_CONTENT;

public class DownloadUtils {
    public static void downloadFile(FileObjectResult fileObject, String mode,
                                    HttpServletRequest request, HttpServletResponse response) throws IOException {
        try (InputStream is = fileObject.getContent()) {
            if (is == null) {
                response.setStatus(SC_NOT_FOUND);
                response.getWriter().write("File data not found in storage");
                return;
            }

            long fileSize = fileObject.getSize();
            // 处理Range请求
            long[] range = parseRangeHeader(request, fileSize);
            long start = range[0];
            long end = range[1];
            long length = end - start + 1;

            String mimeTypeName = fileObject.getMimeTypeName();
            String contentType = mimeTypeName != null ? mimeTypeName : "application/octet-stream";

            // 如果是文本类型的文件，明确指定UTF-8编码
            if (TextMimeUtil.isTextBased(mimeTypeName)) {
                contentType += "; charset=UTF-8";
            }
            response.setContentType(contentType);
            String encodedFileName = URLEncoder.encode(fileObject.getName(), StandardCharsets.UTF_8).replace("+", "%20");
            String dispositionType = "inline".equals(mode) ? "inline" : "attachment";
            response.setHeader("Content-Disposition", dispositionType + "; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName);
            response.setHeader("Accept-Ranges", "bytes");

            // 根据是否是范围请求设置不同的响应头
            if (request.getHeader("Range") != null) {
                response.setStatus(SC_PARTIAL_CONTENT);
                response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
                response.setContentLengthLong(length);
            } else {
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentLengthLong(fileSize);
            }

            // 跳过起始字节
            if (start > 0) {
                long bytesToSkip = start;
                while (bytesToSkip > 0) {
                    long skipped = is.skip(bytesToSkip);
                    if (skipped <= 0) {
                        // 如果无法再跳过任何字节，但还没到目标位置，说明流出了问题
                        throw new IOException("Unable to skip to the specified start position.");
                    }
                    bytesToSkip -= skipped;
                }
            }

            // 流式传输
            try (OutputStream os = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long bytesToWrite = length;
                while (bytesToWrite > 0 && (bytesRead = is.read(buffer, 0, (int) Math.min(buffer.length, bytesToWrite))) != -1) {
                    os.write(buffer, 0, bytesRead);
                    bytesToWrite -= bytesRead;
                }
                os.flush();
            }
        }
    }

    /**
     * 解析Range头，返回[start, end]
     */
    private static long[] parseRangeHeader(HttpServletRequest request, long fileSize) {
        String rangeHeader = request.getHeader("Range");
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            return new long[]{0, fileSize - 1};
        }
        // "bytes=0-499" or "bytes=500-" or "bytes=-500"
        String rangeValue = rangeHeader.substring(6);
        long start = 0, end = fileSize - 1;

        if (rangeValue.startsWith("-")) { // e.g., "-500" (last 500 bytes)
            long lastBytes = Long.parseLong(rangeValue.substring(1));
            start = Math.max(0, fileSize - lastBytes);
        } else {
            String[] parts = rangeValue.split("-");
            start = Long.parseLong(parts[0]);
            if (parts.length > 1 && !parts[1].isEmpty()) {
                end = Long.parseLong(parts[1]);
            }
        }

        // 保证范围有效
        if (start < 0 || start >= fileSize || start > end) {
            return new long[]{0, fileSize - 1};
        }
        return new long[]{start, Math.min(end, fileSize - 1)};
    }
}
