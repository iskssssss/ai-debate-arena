package com.debatearena.service;

import com.debatearena.browser.LoginStatus;
import com.debatearena.browser.ProfileManager;
import com.debatearena.browser.SelectorProvider;
import com.debatearena.browser.YamlSelectorProvider;
import com.debatearena.config.AiPlatformProperties;
import com.debatearena.config.JudgeConfig;
import com.debatearena.controller.dto.ChannelCreateRequest;
import com.debatearena.controller.dto.ChannelItemResponse;
import com.debatearena.controller.dto.ChannelUpdateRequest;
import com.debatearena.controller.dto.ThirdPartyApiTestResponse;
import com.debatearena.judge.DeepSeekApiClient;
import com.debatearena.model.AiPlatform;
import com.debatearena.model.ChannelDefinition;
import com.debatearena.model.ChannelRegistry;
import com.debatearena.model.ChannelType;
import com.debatearena.persistence.ChannelRegistryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 通道注册表管理：内置通道默认化、自定义 API 通道增删改、就绪状态判断。
 */
@Service
@RequiredArgsConstructor
public class ChannelRegistryService {

    private static final String API_CHANNEL_PREFIX = "api-";
    private static final int MAX_CUSTOM_CHANNELS = 8;

    private final ChannelRegistryStore registryStore;
    private final ProfileManager profileManager;
    private final AiPlatformProperties platformProperties;
    private final JudgeConfig judgeConfig;
    private final DeepSeekApiClient apiClient;

    /**
     * 列出全部通道及当前就绪状态。
     */
    public List<ChannelItemResponse> listChannelsWithStatus() {
        ChannelRegistry registry = getOrCreateRegistry();
        List<ChannelItemResponse> result = new ArrayList<>();
        for (ChannelDefinition def : registry.getChannels()) {
            if (!def.isEnabled()) {
                continue;
            }
            result.add(toItemResponse(def));
        }
        return result;
    }

    /**
     * 按 ID 获取通道定义。
     */
    public ChannelDefinition getChannel(String channelId) {
        return getOrCreateRegistry().getChannels().stream()
                .filter(c -> c.getId().equals(channelId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("通道不存在: " + channelId));
    }

    /** 内置通道 ID → 固定展示名称（服务商名，不可修改）。 */
    private static final Map<String, String> BUILTIN_DISPLAY_NAMES = Map.of(
            "chatgpt", "ChatGPT",
            "deepseek", "DeepSeek",
            "gemini", "Gemini"
    );

    /**
     * 更新通道（名称、类型、API 参数等）；内置通道不可修改。
     */
    public ChannelItemResponse updateChannel(String channelId, ChannelUpdateRequest request) {
        ChannelRegistry registry = getOrCreateRegistry();
        ChannelDefinition def = findChannel(registry, channelId);

        if (def.isBuiltin()) {
            throw new IllegalArgumentException("内置通道配置不可修改");
        }

        String prevBaseUrl = def.getBaseUrl();
        String prevModel = def.getModel();

        if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
            def.setDisplayName(request.getDisplayName().trim());
        }
        if (request.getType() != null && request.getType() != def.getType()) {
            throw new IllegalArgumentException("接入方式不可修改");
        }
        if (request.getBaseUrl() != null) {
            def.setBaseUrl(request.getBaseUrl().trim());
        }
        if (request.getModel() != null) {
            def.setModel(request.getModel().trim());
        }
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            def.setApiKey(request.getApiKey().trim());
        }
        if (request.getEnabled() != null) {
            def.setEnabled(request.getEnabled());
        }

        invalidateApiVerificationIfConfigChanged(def, request, prevBaseUrl, prevModel);
        validateChannel(def);
        registryStore.save(registry);
        return toItemResponse(def);
    }

    /**
     * API 配置变更时清除连通性验证状态，须重新检测。
     */
    private void invalidateApiVerificationIfConfigChanged(ChannelDefinition def, ChannelUpdateRequest request,
                                                          String prevBaseUrl, String prevModel) {
        if (def.getType() != ChannelType.API) {
            return;
        }
        if (request.getBaseUrl() != null && !request.getBaseUrl().trim().equals(nullToEmpty(prevBaseUrl))) {
            def.setApiVerified(false);
        }
        if (request.getModel() != null && !request.getModel().trim().equals(nullToEmpty(prevModel))) {
            def.setApiVerified(false);
        }
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            def.setApiVerified(false);
        }
    }

    /**
     * 新增自定义 API 通道。
     */
    public ChannelItemResponse createApiChannel(ChannelCreateRequest request) {
        if (request.getDisplayName() == null || request.getDisplayName().isBlank()) {
            throw new IllegalArgumentException("通道名称不能为空");
        }
        if (request.getBaseUrl() == null || request.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("API 地址不能为空");
        }
        if (request.getApiKey() == null || request.getApiKey().isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        if (request.getModel() == null || request.getModel().isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }

        ChannelRegistry registry = getOrCreateRegistry();
        long customCount = registry.getChannels().stream().filter(c -> !c.isBuiltin()).count();
        if (customCount >= MAX_CUSTOM_CHANNELS) {
            throw new IllegalArgumentException("自定义 API 通道最多 " + MAX_CUSTOM_CHANNELS + " 个");
        }

        ChannelDefinition def = ChannelDefinition.builder()
                .id(API_CHANNEL_PREFIX + UUID.randomUUID().toString().substring(0, 8))
                .builtin(false)
                .type(ChannelType.API)
                .displayName(request.getDisplayName().trim())
                .baseUrl(request.getBaseUrl().trim())
                .apiKey(request.getApiKey().trim())
                .model(request.getModel().trim())
                .enabled(true)
                .build();
        registry.getChannels().add(def);
        registryStore.save(registry);
        return toItemResponse(def);
    }

    /**
     * 删除自定义 API 通道（内置通道不可删）。
     */
    public void deleteChannel(String channelId) {
        ChannelRegistry registry = getOrCreateRegistry();
        ChannelDefinition def = findChannel(registry, channelId);
        if (def.isBuiltin()) {
            throw new IllegalArgumentException("内置通道不可删除");
        }
        registry.getChannels().removeIf(c -> c.getId().equals(channelId));
        registryStore.save(registry);
    }

    /**
     * 检测 API 通道连通性；成功时写入配置并标记为已验证。
     */
    public ThirdPartyApiTestResponse testChannel(String channelId, ChannelUpdateRequest override) {
        ChannelRegistry registry = getOrCreateRegistry();
        ChannelDefinition def = findChannel(registry, channelId);
        if (def.getType() != ChannelType.API) {
            return ThirdPartyApiTestResponse.builder()
                    .success(false)
                    .message("仅 API 通道支持连接检测")
                    .build();
        }

        String baseUrl = pick(override != null ? override.getBaseUrl() : null, def.getBaseUrl());
        String model = pick(override != null ? override.getModel() : null, def.getModel());
        String apiKey = override != null && override.getApiKey() != null && !override.getApiKey().isBlank()
                ? override.getApiKey().trim() : def.getApiKey();
        if (baseUrl == null || baseUrl.isBlank()) {
            return ThirdPartyApiTestResponse.builder().success(false).message("请先填写 API 地址").build();
        }
        if (model == null || model.isBlank()) {
            return ThirdPartyApiTestResponse.builder().success(false).message("请先填写模型名称").build();
        }
        if (apiKey == null || apiKey.isBlank()) {
            return ThirdPartyApiTestResponse.builder().success(false).message("请先填写 API Key").build();
        }
        try {
            String reply = apiClient.chat(baseUrl, apiKey, model,
                    "你是连通性检测助手，请仅回复 OK。", "ping");
            boolean ok = reply != null && !reply.isBlank();
            if (ok) {
                applyApiConfig(def, baseUrl, model, apiKey);
                def.setApiVerified(true);
                registryStore.save(registry);
            }
            return ThirdPartyApiTestResponse.builder()
                    .success(ok)
                    .message(ok ? "连接成功，该通道可参与研讨" : "API 返回空响应")
                    .build();
        } catch (Exception e) {
            return ThirdPartyApiTestResponse.builder()
                    .success(false)
                    .message("连接失败：" + e.getMessage())
                    .build();
        }
    }

    /**
     * 将检测通过的 API 参数写入通道定义。
     */
    private void applyApiConfig(ChannelDefinition def, String baseUrl, String model, String apiKey) {
        def.setBaseUrl(baseUrl.trim());
        def.setModel(model.trim());
        def.setApiKey(apiKey.trim());
    }

    /**
     * 判断通道是否可参与研讨。
     */
    public boolean isChannelReady(ChannelDefinition def) {
        if (!def.isEnabled()) {
            return false;
        }
        if (def.getType() == ChannelType.API) {
            return def.getApiKey() != null && !def.getApiKey().isBlank()
                    && def.getBaseUrl() != null && !def.getBaseUrl().isBlank()
                    && def.getModel() != null && !def.getModel().isBlank()
                    && def.isApiVerified();
        }
        AiPlatform platform = toAiPlatform(def).orElse(null);
        if (platform == null) {
            return false;
        }
        SelectorProvider selectors = loadSelectors(platform);
        return profileManager.isEligibleForDebate(platform, selectors);
    }

    /**
     * 统计已就绪通道数量。
     */
    public int countReadyChannels(List<String> channelIds) {
        int count = 0;
        for (String id : channelIds) {
            if (isChannelReady(getChannel(id))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 将内置通道 ID 映射为 AiPlatform。
     */
    public Optional<AiPlatform> toAiPlatform(ChannelDefinition def) {
        if (def.getPlatform() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(AiPlatform.valueOf(def.getPlatform().trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 通过通道 ID 解析 AiPlatform（仅内置浏览器/API 槽位）。
     */
    public Optional<AiPlatform> toAiPlatform(String channelId) {
        try {
            ChannelDefinition def = getChannel(channelId);
            return toAiPlatform(def);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 获取通道展示名称。
     */
    public String getDisplayName(String channelId) {
        return getChannel(channelId).getDisplayName();
    }

    /**
     * 解析研讨参与通道 ID 列表。
     */
    public List<String> resolveParticipantChannelIds(List<String> requestedIds) {
        ChannelRegistry registry = getOrCreateRegistry();
        if (requestedIds == null || requestedIds.isEmpty()) {
            return registry.getChannels().stream()
                    .filter(ChannelDefinition::isEnabled)
                    .filter(c -> c.isBuiltin())
                    .map(ChannelDefinition::getId)
                    .toList();
        }
        return new ArrayList<>(requestedIds);
    }

    /** 获取或初始化默认注册表，并校正内置通道名称。 */
    private ChannelRegistry getOrCreateRegistry() {
        ChannelRegistry registry = registryStore.load();
        if (registry == null || registry.getChannels() == null || registry.getChannels().isEmpty()) {
            registry = buildDefaultRegistry();
            registryStore.save(registry);
            return registry;
        }
        if (normalizeBuiltinChannels(registry)) {
            registryStore.save(registry);
        }
        return registry;
    }

    /**
     * 将内置通道名称校正为服务商固定名。
     *
     * @return 是否发生变更
     */
    private boolean normalizeBuiltinChannels(ChannelRegistry registry) {
        boolean changed = false;
        for (ChannelDefinition def : registry.getChannels()) {
            if (def.isBuiltin()) {
                String canonical = resolveBuiltinDisplayName(def.getId());
                if (!canonical.equals(def.getDisplayName())) {
                    def.setDisplayName(canonical);
                    changed = true;
                }
                if (def.getType() != ChannelType.BROWSER) {
                    def.setType(ChannelType.BROWSER);
                    def.setBaseUrl(null);
                    def.setModel(null);
                    def.setApiKey(null);
                    changed = true;
                }
            } else if (def.getType() != ChannelType.API) {
                def.setType(ChannelType.API);
                changed = true;
            }
        }
        return changed;
    }

    /** 解析内置通道的固定展示名称。 */
    private String resolveBuiltinDisplayName(String channelId) {
        String name = BUILTIN_DISPLAY_NAMES.get(channelId);
        if (name == null) {
            throw new IllegalArgumentException("未知内置通道: " + channelId);
        }
        return name;
    }

    /** 构建三个内置通道的默认配置。 */
    private ChannelRegistry buildDefaultRegistry() {
        List<ChannelDefinition> channels = new ArrayList<>();
        channels.add(builtinChannel("chatgpt", AiPlatform.CHATGPT));
        channels.add(builtinChannel("deepseek", AiPlatform.DEEPSEEK));
        channels.add(builtinChannel("gemini", AiPlatform.GEMINI));
        return ChannelRegistry.builder().channels(channels).build();
    }

    private ChannelDefinition builtinChannel(String id, AiPlatform platform) {
        return ChannelDefinition.builder()
                .id(id)
                .builtin(true)
                .type(ChannelType.BROWSER)
                .displayName(resolveBuiltinDisplayName(id))
                .platform(platform.name())
                .enabled(true)
                .build();
    }

    private ChannelDefinition findChannel(ChannelRegistry registry, String channelId) {
        return registry.getChannels().stream()
                .filter(c -> c.getId().equals(channelId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("通道不存在: " + channelId));
    }

    private void validateChannel(ChannelDefinition def) {
        if (def.getDisplayName() == null || def.getDisplayName().isBlank()) {
            throw new IllegalArgumentException("通道名称不能为空");
        }
        if (def.getType() == ChannelType.API) {
            if (def.getBaseUrl() == null || def.getBaseUrl().isBlank()) {
                throw new IllegalArgumentException("API 通道须填写 API 地址");
            }
            if (def.getModel() == null || def.getModel().isBlank()) {
                throw new IllegalArgumentException("API 通道须填写模型名称");
            }
        }
    }

    private ChannelItemResponse toItemResponse(ChannelDefinition def) {
        boolean ready = isChannelReady(def);
        ChannelItemResponse.ChannelItemResponseBuilder builder = ChannelItemResponse.builder()
                .id(def.getId())
                .builtin(def.isBuiltin())
                .type(def.getType())
                .displayName(def.getDisplayName())
                .platform(def.getPlatform())
                .enabled(def.isEnabled())
                .baseUrl(def.getBaseUrl())
                .model(def.getModel())
                .apiKeyConfigured(def.getApiKey() != null && !def.getApiKey().isBlank())
                .apiKeyMasked(maskApiKey(def.getApiKey()))
                .apiVerified(def.isApiVerified())
                .ready(ready);

        if (def.getType() == ChannelType.BROWSER) {
            toAiPlatform(def).ifPresent(platform -> {
                boolean profileReady = profileManager.isProfileReady(platform);
                LoginStatus status = profileManager.getEffectiveLoginStatus(platform);
                builder.loginStatus(status)
                        .profileReady(profileReady)
                        .message(profileManager.getStatusMessage(platform, status, profileReady));
            });
        } else {
            builder.message(buildApiChannelMessage(def, ready));
        }
        return builder.build();
    }

    /** 生成 API 通道状态说明。 */
    private String buildApiChannelMessage(ChannelDefinition def, boolean ready) {
        if (ready) {
            return "API 通道已验证，可参与研讨";
        }
        boolean configured = def.getApiKey() != null && !def.getApiKey().isBlank()
                && def.getBaseUrl() != null && !def.getBaseUrl().isBlank()
                && def.getModel() != null && !def.getModel().isBlank();
        if (configured && !def.isApiVerified()) {
            return "配置已填写，请点击「检测连接」验证通过后方可参与研讨";
        }
        return "请完善 API 地址、Key 与模型";
    }

    private SelectorProvider loadSelectors(AiPlatform platform) {
        String key = platform.name().toLowerCase();
        String url = platformProperties.getPlatforms().getOrDefault(key, new AiPlatformProperties.PlatformConfig()).getUrl();
        if (url == null || url.isBlank()) {
            url = "https://" + platform.getDomain();
        }
        return YamlSelectorProvider.load(key, url);
    }

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

    private String pick(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
