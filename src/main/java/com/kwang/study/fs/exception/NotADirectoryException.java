package com.kwang.study.fs.exception;

public class NotADirectoryException extends RuntimeException {
    private static final long serialVersionUID = -1L;
    public NotADirectoryException(String path) {
        super("Path is not a directory: " + path);
    }
}
