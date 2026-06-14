package com.debatearena.service;

import com.debatearena.config.JudgeConfig;
import com.debatearena.controller.dto.ThirdPartyApiSettingsRequest;
import com.debatearena.controller.dto.ThirdPartyApiSettingsResponse;
import com.debatearena.controller.dto.ThirdPartyApiTestResponse;
import com.debatearena.judge.DeepSeekApiClient;
import com.debatearena.model.ThirdPartyApiSettings;
import com.debatearena.persistence.ThirdPartyApiSettingsStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 三方 API 整理服务配置管理：读取默认值、持久化、连通性检测。
 */
@Service
@RequiredArgsConstructor
public class ThirdPartyApiSettingsService {

    private final ThirdPartyApiSettingsStore settingsStore;
    private final JudgeConfig judgeConfig;
    private final DeepSeekApiClient apiClient;

    /**
     * 获取当前有效配置（合并本机保存项与 application.yml 默认值）。
     */
    public ThirdPartyApiSettings getEffectiveSettings() {
        ThirdPartyApiSettings saved = settingsStore.load();
        if (saved == null) {
            return ThirdPartyApiSettings.builder()
                    .baseUrl(judgeConfig.getBaseUrl())
                    .model(judgeConfig.getDefaultModel())
                    .build();
        }
        return ThirdPartyApiSettings.builder()
                .baseUrl(nonBlank(saved.getBaseUrl(), judgeConfig.getBaseUrl()))
                .apiKey(saved.getApiKey())
                .model(nonBlank(saved.getModel(), judgeConfig.getDefaultModel()))
                .build();
    }

    /**
     * 返回供前端展示的配置（API Key 脱敏）。
     */
    public ThirdPartyApiSettingsResponse getDisplaySettings() {
        ThirdPartyApiSettings effective = getEffectiveSettings();
        String apiKey = effective.getApiKey();
        return ThirdPartyApiSettingsResponse.builder()
                .baseUrl(effective.getBaseUrl())
                .model(effective.getModel())
                .apiKeyConfigured(apiKey != null && !apiKey.isBlank())
                .apiKeyMasked(maskApiKey(apiKey))
                .build();
    }

    /**
     * 保存三方 API 配置；apiKey 为空时保留已有 Key。
     */
    public ThirdPartyApiSettingsResponse save(ThirdPartyApiSettingsRequest request) {
        if (request.getBaseUrl() == null || request.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("API 地址不能为空");
        }
        if (request.getModel() == null || request.getModel().isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }

        ThirdPartyApiSettings existing = settingsStore.load();
        String apiKey = request.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = existing != null ? existing.getApiKey() : null;
        } else {
            apiKey = apiKey.trim();
        }

        ThirdPartyApiSettings toSave = ThirdPartyApiSettings.builder()
                .baseUrl(request.getBaseUrl().trim())
                .apiKey(apiKey)
                .model(request.getModel().trim())
                .build();
        settingsStore.save(toSave);
        return getDisplaySettings();
    }

    /**
     * 清除本机保存的 API 配置。
     */
    public void clear() {
        settingsStore.delete();
    }

    /**
     * 检测 API 是否可用（发送最小对话请求）。
     */
    public ThirdPartyApiTestResponse test(ThirdPartyApiSettingsRequest request) {
        ThirdPartyApiSettings effective = resolveForTest(request);
        if (effective.getApiKey() == null || effective.getApiKey().isBlank()) {
            return ThirdPartyApiTestResponse.builder()
                    .success(false)
                    .message("请先填写 API Key")
                    .build();
        }
        try {
            String reply = apiClient.chat(
                    effective.getBaseUrl(),
                    effective.getApiKey(),
                    effective.getModel(),
                    "你是一个连通性检测助手，请仅回复 OK。",
                    "ping"
            );
            boolean ok = reply != null && !reply.isBlank();
            return ThirdPartyApiTestResponse.builder()
                    .success(ok)
                    .message(ok ? "连接成功" : "API 返回空响应")
                    .build();
        } catch (Exception e) {
            return ThirdPartyApiTestResponse.builder()
                    .success(false)
                    .message("连接失败：" + e.getMessage())
                    .build();
        }
    }

    /**
     * 解析用于发起研讨的 API Key：请求体优先，否则使用本机配置。
     */
    public String resolveApiKey(String requestApiKey) {
        if (requestApiKey != null && !requestApiKey.isBlank()) {
            return requestApiKey.trim();
        }
        ThirdPartyApiSettings effective = getEffectiveSettings();
        return effective.getApiKey() != null ? effective.getApiKey().trim() : null;
    }

    /**
     * 解析用于发起研讨的模型：请求体优先，否则使用本机配置。
     */
    public String resolveModel(String requestModel) {
        if (requestModel != null && !requestModel.isBlank()) {
            return requestModel.trim();
        }
        return getEffectiveSettings().getModel();
    }

    /**
     * 判断是否已具备 API 整理能力（本机或请求中提供了 Key）。
     */
    public boolean hasConfiguredApiKey(String requestApiKey) {
        String key = resolveApiKey(requestApiKey);
        return key != null && !key.isBlank();
    }

    /** 合并检测请求与已保存配置。 */
    private ThirdPartyApiSettings resolveForTest(ThirdPartyApiSettingsRequest request) {
        ThirdPartyApiSettings effective = getEffectiveSettings();
        String baseUrl = request != null && request.getBaseUrl() != null && !request.getBaseUrl().isBlank()
                ? request.getBaseUrl().trim() : effective.getBaseUrl();
        String model = request != null && request.getModel() != null && !request.getModel().isBlank()
                ? request.getModel().trim() : effective.getModel();
        String apiKey = request != null && request.getApiKey() != null && !request.getApiKey().isBlank()
                ? request.getApiKey().trim() : effective.getApiKey();
        return ThirdPartyApiSettings.builder()
                .baseUrl(baseUrl)
                .model(model)
                .apiKey(apiKey)
                .build();
    }

    /** 对 API Key 脱敏展示。 */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, Math.min(4, trimmed.length())) + "****"
                + trimmed.substring(trimmed.length() - 4);
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }
}
