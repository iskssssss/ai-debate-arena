package com.debatearena.browser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 YAML 的选择器提供者实现。
 * <p>
 * 从 {@code selectors/{platform}-selectors.yml} 加载选择器配置，
 * 提供多级 fallback 链。
 */
@Slf4j
@Component
public class YamlSelectorProvider implements SelectorProvider {

    private final Map<String, Object> selectors;
    private final String platformUrl;
    private final String platformName;

    public YamlSelectorProvider() {
        // 默认使用空配置，实际由子类或工厂方法创建
        this.selectors = Map.of();
        this.platformUrl = "";
        this.platformName = "unknown";
    }

    /**
     * 从 classpath 加载指定平台的选择器 YAML。
     */
    public static YamlSelectorProvider load(String platformName, String platformUrl) {
        String yamlPath = "selectors/" + platformName.toLowerCase() + "-selectors.yml";
        log.info("加载选择器配置: {}", yamlPath);

        try (InputStream is = YamlSelectorProvider.class.getClassLoader()
                .getResourceAsStream(yamlPath)) {
            if (is == null) {
                throw new IllegalStateException("找不到选择器配置文件: " + yamlPath);
            }
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(is);
            @SuppressWarnings("unchecked")
            Map<String, Object> sel = (Map<String, Object>) data.getOrDefault("selectors", Map.of());
            return new YamlSelectorProvider(sel, platformUrl, platformName);
        } catch (Exception e) {
            log.error("加载选择器配置失败: {} — {}", yamlPath, e.getMessage());
            throw new RuntimeException("无法加载选择器配置: " + yamlPath, e);
        }
    }

    private YamlSelectorProvider(Map<String, Object> selectors, String platformUrl, String platformName) {
        this.selectors = selectors;
        this.platformUrl = platformUrl;
        this.platformName = platformName;
    }

    @Override
    public String getInputSelector() {
        return getString("input");
    }

    @Override
    public String getInputFallbackSelector() {
        return getString("input-fallback");
    }

    @Override
    public String getSubmitSelector() {
        return getString("submit");
    }

    @Override
    public String getSubmitFallbackSelector() {
        return getString("submit-fallback");
    }

    @Override
    public String getLatestResponseSelector() {
        return getString("latest-response");
    }

    @Override
    public String getLoginIndicator() {
        return getString("login-indicator");
    }

    @Override
    public String getLoginIndicatorFallback() {
        return getString("login-indicator-fallback");
    }

    @Override
    public String getResponseWaitIndicator() {
        return getString("response-wait-indicator");
    }

    @Override
    public String getCopyResponseSelector() {
        return getString("copy-response");
    }

    @Override
    public String getCopyResponseFallbackSelector() {
        return getString("copy-response-fallback");
    }

    @Override
    public String getStopGeneratingSelector() {
        return getString("stop-generating");
    }

    @Override
    public String getStopGeneratingFallback() {
        return getString("stop-generating-fallback");
    }

    @Override
    public String getNewChatSelector() {
        return getString("new-chat");
    }

    @Override
    public String getNewChatFallbackSelector() {
        return getString("new-chat-fallback");
    }

    @Override
    public String getPlatformUrl() {
        return platformUrl;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Integer> getTimeouts() {
        Object timeoutsObj = selectors.get("timeouts");
        if (timeoutsObj instanceof Map) {
            return (Map<String, Integer>) timeoutsObj;
        }
        // 默认超时
        Map<String, Integer> defaults = new LinkedHashMap<>();
        defaults.put("page-load", 15000);
        defaults.put("input-ready", 10000);
        defaults.put("response-complete", 120000);
        return defaults;
    }

    @Override
    public List<String> getInputSelectorChain() {
        List<String> chain = new ArrayList<>();
        addIfNotNull(chain, getInputSelector());
        addIfNotNull(chain, getInputFallbackSelector());
        addIfNotNull(chain, getString("input-fallback-2"));
        addIfNotNull(chain, getString("input-fallback-3"));
        return chain;
    }

    @Override
    public List<String> getSubmitSelectorChain() {
        List<String> chain = new ArrayList<>();
        addIfNotNull(chain, getSubmitSelector());
        addIfNotNull(chain, getSubmitFallbackSelector());
        return chain;
    }

    @Override
    public List<String> getCopyResponseSelectorChain() {
        List<String> chain = new ArrayList<>();
        addIfNotNull(chain, getCopyResponseSelector());
        addIfNotNull(chain, getCopyResponseFallbackSelector());
        addIfNotNull(chain, getResponseWaitIndicator());
        return chain;
    }

    private String getString(String key) {
        Object val = selectors.get(key);
        return val != null ? val.toString() : null;
    }

    private void addIfNotNull(List<String> list, String value) {
        if (value != null && !value.isBlank()) {
            list.add(value);
        }
    }

    @Override
    public String toString() {
        return "YamlSelectorProvider{" + platformName + ", url=" + platformUrl + '}';
    }
}
