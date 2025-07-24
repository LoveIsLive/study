package com.kwang.study.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "Node not found")
public class NodeNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 8913598783669339889L;

    public NodeNotFoundException() {
        super("The requested node does not exist.");
    }

    public NodeNotFoundException(String message) {
        super(message);
    }
}