package com.debatearena.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 三方 API 配置查询响应（API Key 脱敏）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThirdPartyApiSettingsResponse {

    /** API Base URL。 */
    private String baseUrl;

    /** 默认模型。 */
    private String model;

    /** 是否已配置 API Key。 */
    private boolean apiKeyConfigured;

    /** 脱敏后的 API Key 展示（如 sk-****abcd）。 */
    private String apiKeyMasked;
}
