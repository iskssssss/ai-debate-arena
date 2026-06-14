package com.debatearena.model;

/**
 * 三个参与辩论的 AI 平台。
 */
public enum AiPlatform {
    GEMINI("gemini.google.com"),
    CHATGPT("chatgpt.com"),
    DEEPSEEK("chat.deepseek.com");

    private final String domain;

    AiPlatform(String domain) {
        this.domain = domain;
    }

    public String getDomain() {
        return domain;
    }
}
