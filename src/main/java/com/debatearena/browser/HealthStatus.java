package com.debatearena.browser;

import lombok.Getter;

/**
 * 平台适配器健康状态。
 */
public enum HealthStatus {
    /** 健康，可正常使用。 */
    HEALTHY,
    /** 部分功能异常（如选择器失效但页面可访问）。 */
    DEGRADED,
    /** 不可用（页面无法访问、登录过期等）。 */
    UNHEALTHY
}
