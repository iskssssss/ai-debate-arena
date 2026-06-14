package com.debatearena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 裁判（DeepSeek API）相关配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "judge")
public class JudgeConfig {

    /** DeepSeek API Base URL（OpenAI 兼容格式）。 */
    private String baseUrl = "https://api.deepseek.com";

    /** 默认模型。 */
    private String defaultModel = "deepseek-v4-flash";

    /** API 调用超时（秒）。 */
    private int timeoutSeconds = 120;
}
