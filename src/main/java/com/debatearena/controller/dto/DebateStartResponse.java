package com.debatearena.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 辩论启动响应 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateStartResponse {

    /** 会话 ID。 */
    private String sessionId;

    /** 辩论状态。 */
    private String status;

    /** 提示消息。 */
    private String message;
}
