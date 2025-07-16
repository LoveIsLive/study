package com.kwang.study.common;

import lombok.Data;

@Data
public class R<T> {
    private int code;
    private String message;
    private T data;

    public static <T> R<T> success(T data) {
        return new R<>(200, "Success", data);
    }

    public static <T> R<T> error(int code, String message) {
        return new R<>(code, message, null);
    }

    public R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

}
