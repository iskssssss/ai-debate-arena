package com.debatearena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 平台端点配置，绑定到 application.yml 中的 {@code ai-platforms.*} 配置段。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-platforms")
public class AiPlatformProperties {

    /** 平台配置映射（key = gemini / chatgpt / deepseek）。 */
    private Map<String, PlatformConfig> platforms = new HashMap<>();

    @Data
    public static class PlatformConfig {
        /** 平台显示名称。 */
        private String name;
        /** 平台首页 URL。 */
        private String url;
        /** 是否启用该平台。 */
        private boolean enabled = true;
    }
}
