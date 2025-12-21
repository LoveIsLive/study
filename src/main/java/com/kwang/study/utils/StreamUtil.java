package com.kwang.study.utils;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public class StreamUtil {
    public static byte[] readExactly(InputStream inputStream, int size)
            throws IOException {
        if (inputStream == null) {
            throw new NullPointerException("InputStream cannot be null");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive, got: " + size);
        }

        byte[] buffer = new byte[size];
        int totalRead = 0;

        while (totalRead < size) {
            int read = inputStream.read(buffer, totalRead, size - totalRead);
            if (read == -1) {
                // 到达流末尾但未读取足够字节
                throw new EOFException(
                        String.format("Unexpected end of stream: expected %d bytes, but only read %d bytes",
                                size, totalRead));
            }
            totalRead += read;
        }

        return buffer;
    }
}
