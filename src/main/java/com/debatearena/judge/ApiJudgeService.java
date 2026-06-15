package com.debatearena.judge;

import com.debatearena.model.*;
import com.debatearena.service.DebateMaterialBuilder;
import com.debatearena.service.ThirdPartyApiSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API 整理服务 —— 每轮结束后调用 DeepSeek API 整理各讨论方信息。
 * <p>
 * API Key 仅保存在内存中，不写入持久化快照。
 */
@Slf4j
@Service("apiJudgeService")
@RequiredArgsConstructor
public class ApiJudgeService implements JudgeService {

    private final DeepSeekApiClient apiClient;
    private final ThirdPartyApiSettingsService apiSettingsService;
    private final DebateMaterialBuilder materialBuilder;

    /** sessionId → API Key（内存暂存，研讨结束后清除）。 */
    private final Map<String, String> sessionApiKeys = new ConcurrentHashMap<>();

    private volatile String roundSystemPrompt;
    private volatile String finalSystemPrompt;

    @Override
    public void registerSession(String sessionId, DebateSession session) {
        // API 模式下不需要特殊初始化，API Key 由 retryRoundSummary 或外部注入
    }

    /**
     * 注册本场研讨的 API Key（仅内存，不落盘）。
     */
    public void registerApiKey(String sessionId, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            sessionApiKeys.put(sessionId, apiKey.trim());
        }
    }

    @Override
    public void cleanup(String sessionId) {
        sessionApiKeys.remove(sessionId);
    }

    @Override
    public boolean isJudgeEnabled(DebateSession session) {
        return session.isJudgeEnabled() && sessionApiKeys.containsKey(session.getSessionId());
    }

    @Override
    public JudgeRoundRecord summarizeRound(DebateSession session, DebateRound round) {
        String apiKey = sessionApiKeys.get(session.getSessionId());
        if (apiKey == null) {
            return JudgeRoundRecord.failure("未配置整理服务 API Key");
        }
        try {
            String userPrompt = buildRoundUserPrompt(session, round);
            String analysis = apiClient.chat(
                    apiSettingsService.getEffectiveSettings().getBaseUrl(),
                    apiKey, session.getJudgeModel(),
                    getRoundSystemPrompt(), userPrompt);
            String cleaned = DocumentContentSanitizer.sanitize(analysis);
            log.info("⚖️ 第 {} 轮整理完成 ({} 字符)", round.getRoundNumber(), cleaned.length());
            return JudgeRoundRecord.success(cleaned);
        } catch (Exception e) {
            log.error("本轮整理失败 — round={}: {}", round.getRoundNumber(), e.getMessage());
            return JudgeRoundRecord.failure(e.getMessage());
        }
    }

    @Override
    public String generateDocumentContent(DebateSession session, String systemPrompt, String userPrompt) {
        String apiKey = sessionApiKeys.get(session.getSessionId());
        if (apiKey == null) {
            throw new IllegalStateException("未配置整理服务 API Key");
        }
        return apiClient.chat(
                apiSettingsService.getEffectiveSettings().getBaseUrl(),
                apiKey, session.getJudgeModel(), systemPrompt, userPrompt);
    }

    /**
     * 重新整理指定轮次（用于此前失败的轮次整理重试）。
     */
    public JudgeRoundRecord retryRoundSummary(DebateSession session, int roundNumber, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            registerApiKey(session.getSessionId(), apiKey);
        }
        DebateRound round = session.getRounds().stream()
                .filter(r -> r.getRoundNumber() == roundNumber)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在: " + roundNumber));
        return summarizeRound(session, round);
    }

    @Override
    public JudgeRoundRecord summarizeFinal(DebateSession session) {
        String apiKey = sessionApiKeys.get(session.getSessionId());
        if (apiKey == null) {
            return JudgeRoundRecord.failure("未配置整理服务 API Key");
        }
        try {
            String userPrompt = buildFinalUserPrompt(session);
            String analysis = apiClient.chat(
                    apiSettingsService.getEffectiveSettings().getBaseUrl(),
                    apiKey, session.getJudgeModel(),
                    getFinalSystemPrompt(), userPrompt);
            String cleaned = DocumentContentSanitizer.sanitize(analysis);
            log.info("⚖️ 最终整理报告完成 ({} 字符)", cleaned.length());
            return JudgeRoundRecord.success(cleaned);
        } catch (Exception e) {
            log.error("最终整理报告生成失败: {}", e.getMessage());
            return JudgeRoundRecord.failure(e.getMessage());
        }
    }

    // ---- Prompt building (also used by ChannelJudgeService via package-private access) ----

    /**
     * 构建单轮整理用户提示词，包含各讨论方 prompt 与 response。
     */
    String buildRoundUserPrompt(DebateSession session, DebateRound round) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 需求描述 ===\n").append(session.getTopic()).append("\n\n");
        sb.append("=== 轮次信息 ===\n");
        sb.append("第 ").append(round.getRoundNumber()).append(" 轮 / 类型: ")
                .append(round.getRoundType()).append("\n");
        sb.append("参与讨论方: ").append(materialBuilder.countParticipants(session)).append(" 人\n\n");

        if (round.getConvergenceResult() != null) {
            ConvergenceResult c = round.getConvergenceResult();
            sb.append("=== 收敛检测 ===\n");
            sb.append("minPairwise=").append(String.format("%.4f", c.getMinPairwiseSimilarity()));
            sb.append(", avgPairwise=").append(String.format("%.4f", c.getAverageSimilarity()));
            sb.append(", converged=").append(c.isConverged()).append("\n\n");
        }

        materialBuilder.appendRoundMaterials(sb, session, round, 6000, 6000);
        return sb.toString();
    }

    /**
     * 构建最终整理用户提示词，汇总各轮整理记录与讨论方回答。
     */
    String buildFinalUserPrompt(DebateSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 需求描述 ===\n").append(session.getTopic()).append("\n");
        sb.append("状态: ").append(session.getStatus()).append("\n");
        sb.append("总轮数: ").append(session.getCurrentRoundNumber()).append("\n\n");

        for (DebateRound round : session.getRounds()) {
            sb.append("\n######## 第 ").append(round.getRoundNumber())
                    .append(" 轮 (").append(round.getRoundType()).append(") ########\n");

            if (round.getJudgeRecord() != null && round.getJudgeRecord().isSuccess()) {
                sb.append("[本轮整理摘要]\n")
                        .append(truncate(round.getJudgeRecord().getAnalysis(), 3000))
                        .append("\n");
            }

            for (DebateMaterialBuilder.RoundParticipantMaterial item
                    : materialBuilder.listRoundMaterials(session, round)) {
                if (item.response() != null && item.response().getContent() != null) {
                    sb.append("\n[").append(item.label()).append(" 回答摘要]\n")
                            .append(truncate(item.response().getContent(), 2000)).append("\n");
                }
            }
        }
        return sb.toString();
    }

    static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n... (已截断)";
    }

    String getRoundSystemPrompt() {
        if (roundSystemPrompt == null) {
            roundSystemPrompt = loadResource("templates/judge/round-system-prompt.txt");
        }
        return roundSystemPrompt;
    }

    String getFinalSystemPrompt() {
        if (finalSystemPrompt == null) {
            finalSystemPrompt = loadResource("templates/judge/final-system-prompt.txt");
        }
        return finalSystemPrompt;
    }

    private String loadResource(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("无法加载整理模板: " + path, e);
        }
    }
}
