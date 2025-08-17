package com.kwang.study.fs.exception;

public class NotAFileException extends RuntimeException {
    private static final long serialVersionUID = -1L;
    public NotAFileException(String path) {
        super("Path is not a file: " + path);
    }
}
