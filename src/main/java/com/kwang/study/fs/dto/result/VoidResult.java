package com.kwang.study.fs.dto.result;

public class VoidResult extends BaseResult {
    public static VoidResult success() {
        VoidResult result = new VoidResult();
        result.setSuccess(Boolean.TRUE);
        return result;
    }

    public static VoidResult fail(String msg) {
        VoidResult result = new VoidResult();
        result.setSuccess(Boolean.FALSE);
        result.setErrorMessage(msg);
        return result;
    }
}
