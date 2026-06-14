package com.debatearena.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 三方 API 整理服务配置（OpenAI 兼容格式）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThirdPartyApiSettings {

    /** API Base URL，如 https://api.deepseek.com。 */
    private String baseUrl;

    /** API Key（仅本地配置文件存储，不写研讨快照）。 */
    private String apiKey;

    /** 默认模型名称。 */
    private String model;
}
