package com.debatearena.browser;

import lombok.Getter;

/**
 * 登录状态枚举。
 */
public enum LoginStatus {
    /** 已登录，可以正常使用。 */
    LOGGED_IN,
    /** 需要登录。 */
    LOGIN_REQUIRED,
    /** 检测出错。 */
    ERROR
}
