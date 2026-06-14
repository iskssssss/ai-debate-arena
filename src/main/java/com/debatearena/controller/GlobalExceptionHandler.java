package com.debatearena.controller;

import com.debatearena.adapter.BrowserAutomationException;
import com.debatearena.controller.dto.ErrorResponse;
import com.debatearena.controller.exception.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器 —— 将业务异常映射为统一的 REST 错误响应。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DebateSessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(DebateSessionNotFoundException e) {
        return buildResponse(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(LoginRequiredException.class)
    public ResponseEntity<ErrorResponse> handleLoginRequired(LoginRequiredException e) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", e.getMessage());
    }

    @ExceptionHandler(BrowserAutomationException.class)
    public ResponseEntity<ErrorResponse> handleBrowserError(BrowserAutomationException e) {
        log.error("浏览器操作异常: {}", e.getMessage());
        return buildResponse(HttpStatus.BAD_GATEWAY, "BROWSER_ERROR", e.getMessage());
    }

    @ExceptionHandler(DebateAlreadyCompleteException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyComplete(DebateAlreadyCompleteException e) {
        return buildResponse(HttpStatus.CONFLICT, "ALREADY_COMPLETE", e.getMessage());
    }

    @ExceptionHandler(InsufficientPlatformsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientPlatforms(InsufficientPlatformsException e) {
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, "INSUFFICIENT_PLATFORMS", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadArgument(IllegalArgumentException e) {
        return buildResponse(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("请求参数有误，请检查输入内容");
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception e) {
        log.error("未预期的服务端异常: {}", e.getMessage(), e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "服务暂时不可用，请稍后重试: " + e.getMessage());
    }

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String errorCode, String message) {
        ErrorResponse body = ErrorResponse.builder()
                .status(status.value())
                .errorCode(errorCode)
                .message(message)
                .build();
        return ResponseEntity.status(status).body(body);
    }
}
