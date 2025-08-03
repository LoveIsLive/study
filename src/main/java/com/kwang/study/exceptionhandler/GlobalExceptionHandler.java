package com.kwang.study.exceptionhandler;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.kwang.study.common.R;
import com.kwang.study.exception.InvalidNodePermissionException;
import com.kwang.study.exception.NodeNotFoundException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

import java.io.IOException;
import java.util.stream.Collectors;

//@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleJsonParseError(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.error(HttpStatus.BAD_REQUEST.value(), "Invalid JSON format: " + ex.getMessage()));
    }

    @ExceptionHandler(InvalidFormatException.class)
    public ResponseEntity<R<Void>> handleInvalidFormat(InvalidFormatException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.error(HttpStatus.BAD_REQUEST.value(), "Invalid format in JSON: " + ex.getMessage()));
    }

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<R<Void>> handleTypeMismatchException(TypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.error(HttpStatus.BAD_REQUEST.value(), "Invalid parameter type: " + ex.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<R<Void>> handleDatabaseError(DataAccessException ex) {
        // 可进一步细化异常类型，如 DuplicateKeyException
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Database error: " + ex.getMessage()));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<R<Void>> handleFileUploadError(MultipartException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(R.error(HttpStatus.PAYLOAD_TOO_LARGE.value(), "File size exceeds limit or invalid format"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValidationErrors(MethodArgumentNotValidException ex) {
        String message = ex.getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.error(HttpStatus.BAD_REQUEST.value(), message));
    }

    @ExceptionHandler(NodeNotFoundException.class)
    public ResponseEntity<R<Void>> handleNodeNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(R.error(HttpStatus.NOT_FOUND.value(), "Node not found"));
    }

    @ExceptionHandler(InvalidNodePermissionException.class)
    public ResponseEntity<R<Void>> handleInvalidPermission() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.error(HttpStatus.BAD_REQUEST.value(), "Invalid permission string"));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<R<Void>> handleIOException(IOException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleGeneralException(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Unexpected error: " + ex.getMessage()));
    }
}
