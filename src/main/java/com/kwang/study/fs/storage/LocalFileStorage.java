package com.kwang.study.fs.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class LocalFileStorage implements FileStorage {
    private final Path dir;

    public LocalFileStorage(String dir) throws IOException {
        this(Paths.get(dir));
    }

    public LocalFileStorage(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectory(dir);
        } else if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("指定的路径不是目录");
        }
        this.dir = dir;
    }

    @Override
    public void putFile(String key, InputStream file) throws IOException {
        Path targetPath = dir.resolve(key);
        Files.copy(file, targetPath, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public InputStream getFile(String key) throws IOException {
        if (!contains(key)) {
            return null;
        }
        Path path = dir.resolve(key);
        return Files.newInputStream(path);
    }

    @Override
    public void deleteFile(String key) throws IOException {
        Path path = dir.resolve(key);
        Files.deleteIfExists(path);
    }

    @Override
    public boolean contains(String key) {
        Path path = dir.resolve(key);
        return Files.exists(path);
    }

    @Override
    public OutputStream openFile(String key) throws IOException {
        Path path = dir.resolve(key);
        return Files.newOutputStream(path);
    }
}
