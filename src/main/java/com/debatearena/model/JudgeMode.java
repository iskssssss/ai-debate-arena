package com.debatearena.model;

/**
 * 研讨整理方式枚举。
 */
public enum JudgeMode {
    /** 通过 DeepSeek HTTP API 进行整理（现有行为）。 */
    API,
    /** 通过浏览器自动化通道进行整理。 */
    CHANNEL
}
