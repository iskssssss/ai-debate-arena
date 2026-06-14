package com.debatearena.controller.exception;

/**
 * 辩论会话未找到异常 —— HTTP 404。
 */
public class DebateSessionNotFoundException extends RuntimeException {
    public DebateSessionNotFoundException(String sessionId) {
        super("辩论会话未找到: " + sessionId);
    }
}
