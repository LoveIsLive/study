package com.kwang.study.fs.exception;

public class PathAlreadyExistsException extends RuntimeException {
    private static final long serialVersionUID = -1L;
    public PathAlreadyExistsException(String path) {
        super("Path already exists: " + path);
    }
}
