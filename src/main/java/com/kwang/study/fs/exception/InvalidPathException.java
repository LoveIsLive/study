package com.kwang.study.fs.exception;

public class InvalidPathException extends RuntimeException {
    private static final long serialVersionUID = 8913598783669339889L;

    public InvalidPathException(String path) {
        super("不是合法的unix路径: " + path);
    }
}
