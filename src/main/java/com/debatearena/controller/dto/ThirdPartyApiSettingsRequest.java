package com.debatearena.controller.dto;

import lombok.Data;

/**
 * 三方 API 配置保存请求。
 */
@Data
public class ThirdPartyApiSettingsRequest {

    /** API Base URL。 */
    private String baseUrl;

    /**
     * API Key；为空或 null 时保留已保存的 Key（仅更新地址/模型）。
     */
    private String apiKey;

    /** 默认模型名称。 */
    private String model;
}
