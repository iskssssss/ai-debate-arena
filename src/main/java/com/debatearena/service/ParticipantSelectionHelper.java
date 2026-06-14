package com.debatearena.service;

import com.debatearena.model.AiPlatform;
import com.debatearena.model.DebateRequest;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 研讨参与方选择解析与校验工具。
 */
public final class ParticipantSelectionHelper {

    /** 前端默认展示顺序：通道甲 → 通道乙 → 通道丙。 */
    private static final List<AiPlatform> DEFAULT_ORDER = List.of(
            AiPlatform.CHATGPT, AiPlatform.DEEPSEEK, AiPlatform.GEMINI);

    private static final int MIN_PARTICIPANTS = 2;
    private static final int MAX_ALIAS_LENGTH = 24;

    private ParticipantSelectionHelper() {
    }

    /**
     * 解析用户勾选的通道 ID；未指定时默认全部已启用内置通道。
     */
    public static List<String> resolveChannelIds(DebateRequest request) {
        if (request.getParticipantChannelIds() != null && !request.getParticipantChannelIds().isEmpty()) {
            return new ArrayList<>(request.getParticipantChannelIds());
        }
        if (request.getParticipants() != null && !request.getParticipants().isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (AiPlatform platform : request.getParticipants()) {
                ids.add(platform.name().toLowerCase());
            }
            return ids;
        }
        return List.of("chatgpt", "deepseek", "gemini");
    }

    /**
     * 解析通道自定义名称（key 为通道 ID 或平台枚举名）。
     */
    public static Map<String, String> resolveChannelAliases(DebateRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        if (request.getParticipantAliases() == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : request.getParticipantAliases().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().trim();
            String alias = entry.getValue().trim();
            if (alias.isBlank()) {
                continue;
            }
            result.put(normalizeChannelKey(key), alias);
        }
        return result;
    }

    private static String normalizeChannelKey(String key) {
        if (key.startsWith("api-")) {
            return key;
        }
        try {
            return AiPlatform.valueOf(key.toUpperCase()).name().toLowerCase();
        } catch (IllegalArgumentException e) {
            return key.toLowerCase();
        }
    }

    /**
     * 校验通道选择与自定义名称。
     */
    public static void validateChannelSelection(DebateRequest request) {
        List<String> selected = resolveChannelIds(request);
        if (request.getParticipantChannelIds() != null && !request.getParticipantChannelIds().isEmpty()) {
            if (selected.size() < MIN_PARTICIPANTS) {
                throw new IllegalArgumentException("至少选择 " + MIN_PARTICIPANTS + " 个讨论方参与研讨");
            }
            Set<String> unique = new HashSet<>(selected);
            if (unique.size() != selected.size()) {
                throw new IllegalArgumentException("讨论方不能重复选择");
            }
        }

        Map<String, String> aliases = resolveChannelAliases(request);
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            if (entry.getValue().length() > MAX_ALIAS_LENGTH) {
                throw new IllegalArgumentException(
                        entry.getKey() + " 的讨论方名称不能超过 " + MAX_ALIAS_LENGTH + " 个字符");
            }
            if (!selected.contains(entry.getKey())) {
                throw new IllegalArgumentException("未勾选的讨论方不能设置名称：" + entry.getKey());
            }
        }

        Set<String> aliasValues = new HashSet<>();
        for (String channelId : selected) {
            String alias = aliases.getOrDefault(channelId, "");
            if (!alias.isBlank() && !aliasValues.add(alias)) {
                throw new IllegalArgumentException("讨论方名称不能重复：" + alias);
            }
        }

        validateParticipantSelection(request);
    }

    /**
     * 解析用户勾选的参与平台；未指定时默认三方全部参与。
     *
     * @param request 启动请求
     * @return 有序参与平台列表
     */
    public static List<AiPlatform> resolveSelectedPlatforms(DebateRequest request) {
        if (request.getParticipants() == null || request.getParticipants().isEmpty()) {
            return new ArrayList<>(DEFAULT_ORDER);
        }
        return new ArrayList<>(request.getParticipants());
    }

    /**
     * 解析用户自定义的讨论方名称。
     *
     * @param request 启动请求
     * @return 平台 → 自定义名称
     */
    public static Map<AiPlatform, String> resolveCustomAliases(DebateRequest request) {
        Map<AiPlatform, String> result = new EnumMap<>(AiPlatform.class);
        if (request.getParticipantAliases() == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : request.getParticipantAliases().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            try {
                AiPlatform platform = AiPlatform.valueOf(entry.getKey().trim().toUpperCase());
                String alias = entry.getValue().trim();
                if (!alias.isBlank()) {
                    result.put(platform, alias);
                }
            } catch (IllegalArgumentException ignored) {
                // 忽略未知平台键
            }
        }
        return result;
    }

    /**
     * 校验参与方选择与自定义名称是否合法。
     *
     * @param request 启动请求
     */
    public static void validateParticipantSelection(DebateRequest request) {
        List<AiPlatform> selected = resolveSelectedPlatforms(request);
        if (request.getParticipants() != null && !request.getParticipants().isEmpty()) {
            if (selected.size() < MIN_PARTICIPANTS) {
                throw new IllegalArgumentException("至少选择 " + MIN_PARTICIPANTS + " 个讨论方参与研讨");
            }
            Set<AiPlatform> unique = new HashSet<>(selected);
            if (unique.size() != selected.size()) {
                throw new IllegalArgumentException("讨论方不能重复选择");
            }
        }

        Map<AiPlatform, String> aliases = resolveCustomAliases(request);
        for (Map.Entry<AiPlatform, String> entry : aliases.entrySet()) {
            String alias = entry.getValue();
            if (alias.length() > MAX_ALIAS_LENGTH) {
                throw new IllegalArgumentException(
                        entry.getKey().name() + " 的讨论方名称不能超过 " + MAX_ALIAS_LENGTH + " 个字符");
            }
            if (!selected.contains(entry.getKey())) {
                throw new IllegalArgumentException("未勾选的讨论方不能设置名称：" + entry.getKey().name());
            }
        }

        Set<String> aliasValues = new HashSet<>();
        for (AiPlatform platform : selected) {
            String alias = aliases.getOrDefault(platform, "");
            if (!alias.isBlank() && !aliasValues.add(alias)) {
                throw new IllegalArgumentException("讨论方名称不能重复：" + alias);
            }
        }
    }
}
