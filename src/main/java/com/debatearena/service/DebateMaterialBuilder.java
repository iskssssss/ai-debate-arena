package com.debatearena.service;

import com.debatearena.model.DebateRound;
import com.debatearena.model.DebateSession;
import com.debatearena.model.ParticipantResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 研讨材料构建器 —— 统一从通道或平台维度提取各轮 Prompt/回答，供整理服务与产出文档使用。
 */
@Component
public class DebateMaterialBuilder {

    /**
     * 单轮中某位讨论方的材料条目。
     */
    public record RoundParticipantMaterial(String label, String prompt, ParticipantResponse response) {
    }

    /**
     * 统计本场参与讨论方数量（通道优先）。
     */
    public int countParticipants(DebateSession session) {
        List<String> labels = session.resolveParticipantLabels();
        return labels.isEmpty() ? session.getActiveChannelCount() : labels.size();
    }

    /**
     * 追加参与讨论方名称行。
     */
    public void appendParticipantLabelsLine(StringBuilder sb, DebateSession session) {
        sb.append("参与讨论方: ");
        for (String label : session.resolveParticipantLabels()) {
            sb.append(label).append(" ");
        }
        sb.append("\n");
    }

    /**
     * 列出某轮各讨论方材料（通道优先，兼容旧平台快照）。
     */
    public List<RoundParticipantMaterial> listRoundMaterials(DebateSession session, DebateRound round) {
        List<RoundParticipantMaterial> items = new ArrayList<>();
        List<String> channelIds = resolveMaterialChannelIds(session, round);
        if (!channelIds.isEmpty()) {
            for (String channelId : channelIds) {
                String label = session.getChannelAlias(channelId);
                String prompt = round.getChannelPrompt(channelId);
                ParticipantResponse response = round.getChannelResponse(channelId);
                if (prompt == null && response == null) {
                    continue;
                }
                items.add(new RoundParticipantMaterial(label, prompt, response));
            }
            return items;
        }
        for (var platform : session.getParticipatingPlatforms()) {
            String label = session.getParticipantAlias(platform);
            String prompt = round.getPrompt(platform);
            ParticipantResponse response = round.getResponse(platform);
            if (prompt == null && response == null) {
                continue;
            }
            items.add(new RoundParticipantMaterial(label, prompt, response));
        }
        return items;
    }

    /**
     * 将单轮讨论方材料写入 StringBuilder。
     */
    public void appendRoundMaterials(StringBuilder sb, DebateSession session, DebateRound round,
                                       int promptMaxLen, int responseMaxLen) {
        sb.append("=== 各讨论方材料 ===\n");
        for (RoundParticipantMaterial item : listRoundMaterials(session, round)) {
            sb.append("\n--- ").append(item.label()).append(" ---\n");
            if (item.prompt() != null) {
                sb.append("[发送的提示词]\n").append(truncate(item.prompt(), promptMaxLen)).append("\n\n");
            }
            if (item.response() != null && item.response().getContent() != null) {
                sb.append("[收到的回答]\n")
                        .append(truncate(item.response().getContent(), responseMaxLen))
                        .append("\n");
            }
        }
    }

    /**
     * 获取讨论方在某轮的回答文本（通道优先）。
     */
    public String getRoundResponseText(DebateSession session, DebateRound round, String participantLabel) {
        for (RoundParticipantMaterial item : listRoundMaterials(session, round)) {
            if (item.label().equals(participantLabel)
                    && item.response() != null
                    && item.response().getContent() != null) {
                return item.response().getContent();
            }
        }
        return "";
    }

    /**
     * 获取讨论方最新一轮回答文本（通道优先）。
     */
    public String getLatestResponseText(DebateSession session, String participantLabel) {
        for (int i = session.getRounds().size() - 1; i >= 0; i--) {
            String text = getRoundResponseText(session, session.getRounds().get(i), participantLabel);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    /**
     * 解析用于材料组装的通道 ID 列表。
     */
    private List<String> resolveMaterialChannelIds(DebateSession session, DebateRound round) {
        if (session.getParticipatingChannelIds() != null && !session.getParticipatingChannelIds().isEmpty()) {
            return session.getParticipatingChannelIds();
        }
        if (round.getChannelPrompts() != null && !round.getChannelPrompts().isEmpty()) {
            return new ArrayList<>(round.getChannelPrompts().keySet());
        }
        if (session.getSelectedChannelIds() != null && !session.getSelectedChannelIds().isEmpty()) {
            return session.getSelectedChannelIds();
        }
        return List.of();
    }

    /**
     * 截断过长文本并附加说明。
     */
    public String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "\n... (已截断)";
    }
}
