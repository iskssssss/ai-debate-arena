package com.debatearena.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
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
    }

    /**
     * 记录发往指定平台的提示词。
     */
    public void addPrompt(AiPlatform platform, String prompt) {
        prompts.put(platform, prompt);
    }

    public ParticipantResponse getResponse(AiPlatform platform) {
        return responses.get(platform);
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
