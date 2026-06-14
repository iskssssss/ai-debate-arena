package com.debatearena.controller.exception;

/**
 * 辩论已完成异常 —— HTTP 409。
 */
public class DebateAlreadyCompleteException extends RuntimeException {
    public DebateAlreadyCompleteException(String sessionId) {
        super("辩论已完成，无法操作: " + sessionId);
    }
}
