package com.debatearena.adapter;

/**
 * 浏览器自动化操作异常 —— 当 Playwright 操作失败且重试耗尽时抛出。
 */
public class BrowserAutomationException extends RuntimeException {

    public BrowserAutomationException(String message) {
        super(message);
    }

    public BrowserAutomationException(String message, Throwable cause) {
        super(message, cause);
    }
}
