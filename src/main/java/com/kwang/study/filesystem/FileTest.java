package com.kwang.study.filesystem;

import org.apache.catalina.core.StandardContext;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class FileTest {
    public static void main(String[] args) throws Exception {
//        Path path = Path.of("content");
//        Files.createDirectory(path);
//        InputStream stream = Files.newInputStream(path);
//        System.out.println(new String(stream.readAllBytes()));
//        stream.close();
//        System.out.println(path.toAbsolutePath().toUri());

//        System.out.println(Paths.get("./filedata").toAbsolutePath());
        System.out.println(String.valueOf(Integer.MAX_VALUE));
    }
}
