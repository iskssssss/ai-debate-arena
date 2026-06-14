package com.debatearena.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 辩论中的单轮完整记录——包含各 AI 的回答和批判。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateRound {

    /** 轮次编号（从 1 开始）。 */
    private int roundNumber;

    /** 本轮类型（INITIAL、CRITIQUE、REBUTTAL、CONVERGENCE）。 */
    private RoundType roundType;

    /** 本轮各平台回答。 */
    @Builder.Default
    private Map<AiPlatform, ParticipantResponse> responses = new EnumMap<>(AiPlatform.class);

    /** 本轮各平台发送的提示词。 */
    @Builder.Default
    private Map<AiPlatform, String> prompts = new EnumMap<>(AiPlatform.class);

    /** 本轮各通道发送的提示词（含自定义 API 通道）。 */
    @Builder.Default
    private Map<String, String> channelPrompts = new LinkedHashMap<>();

    /** 本轮各通道回答（含自定义 API 通道）。 */
    @Builder.Default
    private Map<String, ParticipantResponse> channelResponses = new LinkedHashMap<>();

    /** 本轮裁判整理记录（DeepSeek API）。 */
    private JudgeRoundRecord judgeRecord;

    /** 本轮产出的批判意见（仅第 2 轮）。 */
    @Builder.Default
    private Map<AiPlatform, List<Critique>> critiques = new EnumMap<>(AiPlatform.class);

    /** 本轮结束后计算的收敛结果（可能为 null）。 */
    private ConvergenceResult convergenceResult;

    /** 本轮开始时间。 */
    private LocalDateTime startedAt;

    /** 本轮完成时间。 */
    private LocalDateTime completedAt;

    public void addResponse(AiPlatform platform, ParticipantResponse response) {
        responses.put(platform, response);
        if (response != null) {
            channelResponses.put(platform.name().toLowerCase(), response);
        }
    }

    /**
     * 记录发往指定平台的提示词。
     */
    public void addPrompt(AiPlatform platform, String prompt) {
        prompts.put(platform, prompt);
        channelPrompts.put(platform.name().toLowerCase(), prompt);
    }

    /** 记录发往指定通道的提示词。 */
    public void addChannelPrompt(String channelId, String prompt) {
        channelPrompts.put(channelId, prompt);
    }

    public ParticipantResponse getResponse(AiPlatform platform) {
        return responses.get(platform);
    }

    /** 获取指定通道在本轮的提示词。 */
    public String getChannelPrompt(String channelId) {
        return channelPrompts.get(channelId);
    }

    /** 获取指定通道在本轮的回复。 */
    public ParticipantResponse getChannelResponse(String channelId) {
        return channelResponses.get(channelId);
    }

    /** 记录通道回复。 */
    public void addChannelResponse(String channelId, ParticipantResponse response) {
        channelResponses.put(channelId, response);
    }

    /**
     * 获取指定平台在本轮发送的提示词。
     */
    public String getPrompt(AiPlatform platform) {
        return prompts.get(platform);
    }

    public void addCritiques(AiPlatform from, List<Critique> critiqueList) {
        critiques.put(from, critiqueList);
    }

    /**
     * 获取所有批判者针对指定平台的意见。
     */
    public List<Critique> getCritiquesAbout(AiPlatform target) {
        List<Critique> result = new ArrayList<>();
        for (List<Critique> list : critiques.values()) {
            for (Critique c : list) {
                if (c.getTargetPlatform() == target) {
                    result.add(c);
                }
            }
        }
        return result;
    }
}
