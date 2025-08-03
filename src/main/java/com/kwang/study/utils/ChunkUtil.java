package com.kwang.study.utils;

import java.io.IOException;
import java.io.InputStream;

public class ChunkUtil {
    /**
     * 读取流到buff中，如果buffer不够用，返回-1，（注意此方法会在读取buff.len之后尝试再读取一个字节）
     * @param input 输入流
     * @param buffer chunk缓冲块
     * @return 返回实际读取的大小
     */
    public static int readChunk(InputStream input, byte[] buffer) throws IOException {
        int size = 0;
        int chunkSize = buffer.length;
        while (size < chunkSize) {
            try {
                int bytesRead = 0;
                bytesRead = input.read(buffer, size, chunkSize - size);
                if (bytesRead == -1) {
                    return size;
                }
                size += bytesRead;
            } catch (IOException e) {
                throw new IOException("读取数据时发生错误，已读取: " + size + " 字节", e);
            }
        }
        int read = input.read();
        return read == -1 ? size : -1;
    }
}
