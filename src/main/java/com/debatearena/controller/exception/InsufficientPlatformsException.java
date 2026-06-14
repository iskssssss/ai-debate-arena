package com.debatearena.controller.exception;

import java.util.List;

/**
 * 平台不足异常 —— HTTP 503。
 */
public class InsufficientPlatformsException extends RuntimeException {
    public InsufficientPlatformsException(int available) {
        super("可用平台不足（当前 " + available + "，至少需要 2 个），辩论无法继续");
    }

    public InsufficientPlatformsException(int available, List<String> excludedPlatforms) {
        super("可用平台不足（当前 " + available + "，至少需要 2 个）。未参与: "
                + String.join(", ", excludedPlatforms));
    }
}
