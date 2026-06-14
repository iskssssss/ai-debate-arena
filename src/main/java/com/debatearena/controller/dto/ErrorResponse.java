package com.debatearena.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 统一错误响应 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    /** HTTP 状态码。 */
    private int status;

    /** 业务错误码。 */
    private String errorCode;

    /** 人类可读的错误消息。 */
    private String message;

    /** 发生时间。 */
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
