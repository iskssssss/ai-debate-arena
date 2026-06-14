package com.debatearena.model;

/**
 * 通道接入方式：浏览器登录或三方 API 直连。
 */
public enum ChannelType {
    /** 通过 Playwright 浏览器自动化交互。 */
    BROWSER,
    /** 通过 OpenAI 兼容 HTTP API 交互。 */
    API
}
