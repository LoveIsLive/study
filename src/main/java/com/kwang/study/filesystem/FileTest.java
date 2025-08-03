package com.kwang.study.filesystem;

import com.kwang.study.pojo.fs.FileChunk;
import com.kwang.study.utils.HashUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;

public class FileTest {
    public static void main(String[] args) throws Exception {
        LocalFileStorage fileStorage = new LocalFileStorage("filedata");
        LocalFileStorage chunkStorage = new LocalFileStorage("filedata/chunk");
        FileChunk chunk0 = new FileChunk();
        chunk0.setKey("10-0");
        FileChunk chunk1 = new FileChunk();
        chunk1.setKey("10-1");
        FileChunk chunk2 = new FileChunk();
        chunk2.setKey("10-2");
        FileChunk chunk3 = new FileChunk();
        chunk3.setKey("10-3");
        ArrayList<FileChunk> chunks = new ArrayList<>();
        Collections.addAll(chunks, chunk0, chunk1, chunk2, chunk3);
        MessageDigest sha256 = HashUtil.sha256();

        long startTime = System.currentTimeMillis();

        try (OutputStream os = fileStorage.openFile("123456")) {
            for (FileChunk chunk : chunks) {
                try (InputStream is = chunkStorage.getFile(chunk.getKey())) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                        sha256.update(buffer, 0, bytesRead);
                    }
                }
            }

        } catch (IOException e) {

        }

        long endTime = System.currentTimeMillis();
        double executionTimeSeconds = (endTime - startTime) / 1000.0;

        System.out.println("执行耗时: " + executionTimeSeconds + " 秒");
    }
}

class A {
    public static void main(String[] args) {
        Path path = Paths.get("/", "home");
        System.out.println(path.toAbsolutePath());
    }
}