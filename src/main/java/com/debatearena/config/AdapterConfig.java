package com.debatearena.config;

import com.debatearena.adapter.*;
import com.debatearena.browser.YamlSelectorProvider;
import com.debatearena.model.AiPlatform;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 平台适配器配置 —— 将三个平台适配器注册为 Spring Bean，
 * 使 DebateOrchestrator 可以通过 {@code Map<AiPlatform, PlatformAdapter>} 注入。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AdapterConfig {

    private final AiPlatformProperties platformProperties;

    @Bean
    public PlatformAdapter chatGPTAdapter() {
        String url = getPlatformUrl("chatgpt");
        var selectors = YamlSelectorProvider.load("chatgpt", url);
        log.info("📦 注册 ChatGPT 适配器 Bean");
        return new ChatGPTAdapter(selectors, new FallbackSelector());
    }

    @Bean
    public PlatformAdapter geminiAdapter() {
        String url = getPlatformUrl("gemini");
        var selectors = YamlSelectorProvider.load("gemini", url);
        log.info("📦 注册 Gemini 适配器 Bean");
        return new GeminiAdapter(selectors, new FallbackSelector());
    }

    @Bean
    public PlatformAdapter deepSeekAdapter() {
        String url = getPlatformUrl("deepseek");
        var selectors = YamlSelectorProvider.load("deepseek", url);
        log.info("📦 注册 DeepSeek 适配器 Bean");
        return new DeepSeekAdapter(selectors, new FallbackSelector());
    }

    /**
     * 将三个适配器按平台分组的 Map，供 DebateOrchestrator 注入。
     */
    @Bean
    public Map<AiPlatform, PlatformAdapter> platformAdapterMap(
            PlatformAdapter chatGPTAdapter,
            PlatformAdapter geminiAdapter,
            PlatformAdapter deepSeekAdapter) {
        return Map.of(
                AiPlatform.CHATGPT, chatGPTAdapter,
                AiPlatform.GEMINI, geminiAdapter,
                AiPlatform.DEEPSEEK, deepSeekAdapter
        );
    }

    private String getPlatformUrl(String key) {
        var platforms = platformProperties.getPlatforms();
        var config = platforms.get(key);
        return config != null ? config.getUrl() : "";
    }
}
