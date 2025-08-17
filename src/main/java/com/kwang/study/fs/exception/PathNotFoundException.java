package com.kwang.study.fs.exception;

public class PathNotFoundException extends RuntimeException {
    private static final long serialVersionUID = -1L;
    public PathNotFoundException(String path) {
        super("Path not found: " + path);
    }
}
