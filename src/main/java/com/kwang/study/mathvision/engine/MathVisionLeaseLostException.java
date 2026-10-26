package com.kwang.study.mathvision.engine;

/** A fenced execution must never update task state, including from its error handler. */
public class MathVisionLeaseLostException extends RuntimeException {
    public MathVisionLeaseLostException() {
        super("MathVision execution lease is no longer valid");
    }
}
