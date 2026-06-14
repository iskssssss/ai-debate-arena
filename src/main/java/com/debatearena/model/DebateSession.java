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
import java.util.Optional;
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

    /** 用户勾选的参与平台（有序；为空表示默认全部平台）。 */
    @Builder.Default
    private List<AiPlatform> selectedPlatforms = new ArrayList<>();

    /** 用户提交时指定的自定义讨论方名称（assign 前暂存）。 */
    @Builder.Default
    private Map<AiPlatform, String> customParticipantAliases = new EnumMap<>(AiPlatform.class);

    /** 用户勾选的参与通道 ID（有序）。 */
    @Builder.Default
    private List<String> selectedChannelIds = new ArrayList<>();

    /** 本场实际参与研讨的通道 ID（按顺序）。 */
    @Builder.Default
    private List<String> participatingChannelIds = new ArrayList<>();

    /** 通道 ID → 讨论方展示名称。 */
    @Builder.Default
    private Map<String, String> channelAliases = new LinkedHashMap<>();

    /** 用户提交时指定的通道自定义名称。 */
    @Builder.Default
    private Map<String, String> customChannelAliases = new LinkedHashMap<>();

    /** 已失败的通道 ID。 */
    @Builder.Default
    private Set<String> failedChannelIds = ConcurrentHashMap.newKeySet();

    /** 通道失败记录（channelId → 说明）。 */
    @Builder.Default
    private Map<String, String> channelFailures = new LinkedHashMap<>();

    /** 研讨失败时的可读原因（仅整场 FAILED 时展示）。 */
    private String failureReason;

    /** 单方失败记录（降级续跑时保留，供前端警告展示）。 */
    @Builder.Default
    private Map<AiPlatform, String> platformFailures = new LinkedHashMap<>();

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

    /** 获取通道展示名称。 */
    public String getChannelAlias(String channelId) {
        return channelAliases.getOrDefault(channelId, channelId);
    }

    /** 获取用户选择的通道列表；未指定时回退到平台列表映射。 */
    public List<String> getSelectedChannelIdsOrDefault() {
        if (selectedChannelIds != null && !selectedChannelIds.isEmpty()) {
            return selectedChannelIds;
        }
        return getSelectedPlatformsOrAll().stream()
                .map(p -> p.name().toLowerCase())
                .toList();
    }

    /** 获取本场活跃参与通道。 */
    public List<String> getActiveChannelIds() {
        if (!participatingChannelIds.isEmpty()) {
            return participatingChannelIds.stream().filter(this::isChannelActive).toList();
        }
        return getSelectedChannelIdsOrDefault().stream().filter(this::isChannelActive).toList();
    }

    /** 获取除自身外其他活跃通道。 */
    public List<String> getOtherActiveChannelIds(String selfChannelId) {
        return getActiveChannelIds().stream().filter(id -> !id.equals(selfChannelId)).toList();
    }

    public boolean isChannelActive(String channelId) {
        if (selectedChannelIds != null && !selectedChannelIds.isEmpty()
                && !selectedChannelIds.contains(channelId)) {
            return false;
        }
        return !failedChannelIds.contains(channelId);
    }

    public void markChannelFailed(String channelId) {
        failedChannelIds.add(channelId);
        channelRegistryToPlatform(channelId).ifPresent(this::markPlatformFailed);
    }

    /** 记录通道失败说明。 */
    public void recordChannelFailure(String channelId, int roundNum, String reason) {
        markChannelFailed(channelId);
        failedAtRound = Math.max(failedAtRound, roundNum);
        String alias = getChannelAlias(channelId);
        String phase = roundNum > 0 ? "第 " + roundNum + " 轮" : "准备阶段";
        String simplified = simplifyFailureReason(reason);
        channelFailures.put(channelId, alias + " 在" + phase + "未能参与：" + simplified);
        channelRegistryToPlatform(channelId).ifPresent(p ->
                recordPlatformFailure(p, roundNum, reason));
    }

    private Optional<AiPlatform> channelRegistryToPlatform(String channelId) {
        try {
            return Optional.of(AiPlatform.valueOf(channelId.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 当前活跃通道数量。 */
    public int getActiveChannelCount() {
        return getActiveChannelIds().size();
    }

    /** 获取通道最近一轮回答文本。 */
    public String getLatestResponseTextForChannel(String channelId) {
        for (int i = rounds.size() - 1; i >= 0; i--) {
            ParticipantResponse resp = rounds.get(i).getChannelResponse(channelId);
            if (resp != null && resp.getContent() != null) {
                return resp.getContent();
            }
        }
        return "";
    }

    /**
     * 获取除自身外其他活跃通道的最近回答（用于批判/反驳 Prompt）。
     */
    public String getLatestResponsesExceptChannel(String excludeChannelId) {
        StringBuilder sb = new StringBuilder();
        for (String channelId : getOtherActiveChannelIds(excludeChannelId)) {
            String text = getLatestResponseTextForChannel(channelId);
            if (!text.isBlank()) {
                sb.append("=== ").append(getChannelAlias(channelId)).append(" ===\n");
                sb.append(text).append("\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * 获取指定平台最近一次的回答（从最新轮次向前回溯）。
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
     * 记录平台失败；降级续跑时写入 platformFailures，不覆盖整场 failureReason。
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
        platformFailures.put(platform, alias + " 在" + phase + "未能参与：" + simplified);
    }

    /**
     * 标记整场研讨失败并设置终止原因（活跃平台不足等）。
     */
    public void markTerminalFailure(String reason) {
        failureReason = reason;
        updatedAt = LocalDateTime.now();
    }

    /**
     * 获取单方失败的摘要列表（供前端警告展示）。
     */
    public List<String> getPlatformFailureSummaries() {
        return new ArrayList<>(platformFailures.values());
    }

    /**
     * 是否存在单方失败但研讨仍继续的情况。
     */
    public boolean hasPartialFailures() {
        return !platformFailures.isEmpty();
    }

    /**
     * 构建整场终止时的可读原因（活跃平台不足等）。
     */
    public String buildTerminalFailureDetail() {
        if (!platformFailures.isEmpty()) {
            return String.join("；", platformFailures.values())
                    + "（活跃平台不足，至少需要 2 个）";
        }
        return "活跃平台不足（当前 " + getActivePlatformCount() + "，至少需要 2 个）";
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
        if (reason.contains("Timeout") || reason.contains("超时")) {
            return "页面操作超时（可能被弹窗遮挡或网络较慢）";
        }
        if (reason.length() > 120) {
            return reason.substring(0, 117) + "…";
        }
        return reason;
    }

    /**
     * 获取用户选择的参与平台；未指定时返回全部平台（保持旧快照兼容）。
     */
    public List<AiPlatform> getSelectedPlatformsOrAll() {
        if (selectedPlatforms != null && !selectedPlatforms.isEmpty()) {
            return selectedPlatforms;
        }
        return Arrays.asList(AiPlatform.values());
    }

    public boolean isPlatformActive(AiPlatform platform) {
        if (selectedPlatforms != null && !selectedPlatforms.isEmpty()
                && !selectedPlatforms.contains(platform)) {
            return false;
        }
        return !failedPlatforms.contains(platform);
    }

    /** 当前活跃（未失败）的平台数量。 */
    public int getActivePlatformCount() {
        return (int) getSelectedPlatformsOrAll().stream()
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
