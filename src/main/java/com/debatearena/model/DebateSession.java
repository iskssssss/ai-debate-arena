package com.debatearena.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 辩论会话的完整状态——每轮结束后以 JSON 快照持久化。
 * 这是贯穿整个辩论流程的核心领域对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateSession {

    /** 唯一会话标识（UUID）。 */
    private String sessionId;

    /** 辩论主题 / 问题。 */
    private String topic;

    /** 当前状态机状态。 */
    private DebateStatus status;

    /** 所有已完成的轮次，按顺序排列。 */
    @Builder.Default
    private List<DebateRound> rounds = new ArrayList<>();

    /** 已被标记为失败的平台集合。 */
    @Builder.Default
    private Set<AiPlatform> failedPlatforms = ConcurrentHashMap.newKeySet();

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最后更新时间。 */
    private LocalDateTime updatedAt;

    /** 配置的最大轮数。 */
    private int maxRounds;

    /** 配置的收敛阈值。 */
    private double convergenceThreshold;

    /** 是否启用整理服务。 */
    @Builder.Default
    private boolean judgeEnabled = false;

    /** 整理方式：API 或 CHANNEL。 */
    @Builder.Default
    private JudgeMode judgeMode = JudgeMode.API;

    /** 整理使用的模型（API 模式）。 */
    @Builder.Default
    private String judgeModel = "deepseek-v4-flash";

    /** 通道整理模式下的整理方平台。 */
    private AiPlatform judgeChannel;

    /** 用户勾选的赛后产出文档类型 ID 列表。 */
    @Builder.Default
    private List<String> outputDocumentTypes = new ArrayList<>();

    /** 已生成的产出文档（typeId → 记录）。 */
    @Builder.Default
    private Map<String, OutputDocumentRecord> generatedDocuments = new LinkedHashMap<>();

    /** 整场辩论的最终裁判报告。 */
    private JudgeRoundRecord finalJudgeRecord;

    /** 本场实际参与辩论的平台（按甲/乙/丙顺序）。 */
    @Builder.Default
    private List<AiPlatform> participatingPlatforms = new ArrayList<>();

    /** 平台 → 讨论方代号（如 讨论方甲）。 */
    @Builder.Default
    private Map<AiPlatform, String> participantAliases = new EnumMap<>(AiPlatform.class);

    /** 研讨失败时的可读原因（供前端展示）。 */
    private String failureReason;

    /** 失败发生的轮次编号（0 表示准备阶段）。 */
    @Builder.Default
    private int failedAtRound = 0;

    // ---- 便捷方法 ----

    public void addRound(DebateRound round) {
        rounds.add(round);
        updatedAt = LocalDateTime.now();
    }

    public DebateRound getLatestRound() {
        if (rounds.isEmpty()) return null;
        return rounds.get(rounds.size() - 1);
    }

    /**
     * 获取指定平台最近一次的回答（从最新轮次向前回溯）。
     */
    public ParticipantResponse getLatestResponse(AiPlatform platform) {
        for (int i = rounds.size() - 1; i >= 0; i--) {
            ParticipantResponse resp = rounds.get(i).getResponse(platform);
            if (resp != null) return resp;
        }
        return null;
    }

    public String getLatestResponseText(AiPlatform platform) {
        ParticipantResponse resp = getLatestResponse(platform);
        return resp != null ? resp.getContent() : "";
    }

    /**
     * 获取讨论方代号，未分配时返回默认值。
     */
    public String getParticipantAlias(AiPlatform platform) {
        return participantAliases.getOrDefault(platform, "讨论方");
    }

    /**
     * 获取本场活跃参与平台列表。
     */
    public List<AiPlatform> getActivePlatforms() {
        if (!participatingPlatforms.isEmpty()) {
            return participatingPlatforms.stream().filter(this::isPlatformActive).toList();
        }
        return Arrays.stream(AiPlatform.values()).filter(this::isPlatformActive).toList();
    }

    /**
     * 获取除自身外其他活跃讨论方。
     */
    public List<AiPlatform> getOtherActivePlatforms(AiPlatform self) {
        return getActivePlatforms().stream().filter(p -> p != self).toList();
    }

    /**
     * 获取除指定平台外所有活跃平台的最近回答（使用讨论方代号）。
     */
    public String getLatestResponsesExcept(AiPlatform exclude) {
        StringBuilder sb = new StringBuilder();
        for (AiPlatform p : getOtherActivePlatforms(exclude)) {
            ParticipantResponse resp = getLatestResponse(p);
            if (resp != null) {
                sb.append("=== ").append(getParticipantAlias(p)).append(" ===\n");
                sb.append(resp.getContent()).append("\n\n");
            }
        }
        return sb.toString();
    }

    public void markPlatformFailed(AiPlatform platform) {
        failedPlatforms.add(platform);
    }

    /**
     * 记录平台失败并生成用户可读原因。
     *
     * @param platform 失败平台
     * @param roundNum 失败轮次（0 为准备阶段）
     * @param reason   底层异常信息
     */
    public void recordPlatformFailure(AiPlatform platform, int roundNum, String reason) {
        markPlatformFailed(platform);
        failedAtRound = Math.max(failedAtRound, roundNum);
        String alias = getParticipantAlias(platform);
        String phase = roundNum > 0 ? "第 " + roundNum + " 轮" : "准备阶段";
        String simplified = simplifyFailureReason(reason);
        failureReason = alias + " 在" + phase + "失败：" + simplified;
    }

    /**
     * 将底层异常信息简化为用户可读描述。
     */
    private String simplifyFailureReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "未知错误";
        }
        if (reason.contains("Object doesn't exist") || reason.contains("worker@")) {
            return "浏览器连接中断，请重新提交研讨";
        }
        if (reason.contains("所有选择器均未匹配")) {
            return "找不到页面输入框，请检查通道登录状态";
        }
        if (reason.contains("未初始化")) {
            return "浏览器通道未就绪";
        }
        if (reason.contains("超时")) {
            return "响应超时";
        }
        if (reason.length() > 120) {
            return reason.substring(0, 117) + "…";
        }
        return reason;
    }

    public boolean isPlatformActive(AiPlatform platform) {
        return !failedPlatforms.contains(platform);
    }

    /** 当前活跃（未失败）的平台数量。 */
    public int getActivePlatformCount() {
        return (int) Arrays.stream(AiPlatform.values())
                .filter(this::isPlatformActive)
                .count();
    }

    /**
     * 收集所有轮次中针对指定平台的全部批判意见。
     */
    public List<Critique> getCritiquesAbout(AiPlatform platform) {
        List<Critique> result = new ArrayList<>();
        for (DebateRound round : rounds) {
            result.addAll(round.getCritiquesAbout(platform));
        }
        return result;
    }

    public void setStatus(DebateStatus status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    public int getCurrentRoundNumber() {
        return rounds.size();
    }
}
