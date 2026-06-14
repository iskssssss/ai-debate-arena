package com.debatearena.controller.exception;

/**
 * 需要登录异常 —— HTTP 401。
 */
public class LoginRequiredException extends RuntimeException {
    public LoginRequiredException(String platform) {
        super("需要登录: " + platform + "，请先通过浏览器手动登录");
    }
}
