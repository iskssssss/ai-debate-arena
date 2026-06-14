package com.debatearena.prompts;

import com.debatearena.model.AiPlatform;
import com.debatearena.model.DebateRound;
import com.debatearena.model.ParticipantResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩服务 —— 截断辩论历史，防止 Prompt 超出 AI 上下文窗口。
 * <p>
 * 短期策略：保留最近 N 字符，超出部分用截断标记替代。
 * 长期策略（Phase 2）：引入摘要层，而非简单截断。
 */
@Slf4j
@Service
public class CompressionService {

    /** 默认保留的最大字符数（约 1000 tokens）。 */
    private static final int DEFAULT_MAX_CHARS = 4000;

    /** 嵌入其他讨论方方案时的单条上限。 */
    private static final int PEER_RESPONSE_MAX_CHARS = 2500;

    /** 自身历史方案引用上限。 */
    private static final int SELF_RESPONSE_MAX_CHARS = 2000;

    /** 截断标记。 */
    private static final String TRUNCATION_MARKER = "\n\n[已截断早期轮次内容以控制上下文长度...]\n\n";

    /**
     * 压缩历史轮次，保留最近 maxChars 字符的内容。
     *
     * @param history  所有已完成轮次
     * @param maxChars 最大保留字符数
     * @return 压缩后的字符串表示
     */
    public String compress(List<DebateRound> history, int maxChars) {
        if (history == null || history.isEmpty()) {
            return "";
        }

        int effectiveMax = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;

        // 按轮次收集所有内容
        List<String> chunks = new ArrayList<>();
        for (DebateRound round : history) {
            chunks.add(formatRound(round));
        }

        // 从最近轮次向前累积，直到达到字符上限
        StringBuilder sb = new StringBuilder();
        int totalChars = 0;
        boolean truncated = false;

        for (int i = chunks.size() - 1; i >= 0; i--) {
            String chunk = chunks.get(i);
            int chunkLen = chunk.length();
            if (totalChars + chunkLen > effectiveMax && totalChars > 0) {
                truncated = true;
                break;
            }
            sb.insert(0, chunk);
            totalChars += chunkLen;
        }

        if (truncated) {
            sb.insert(0, TRUNCATION_MARKER);
            log.debug("辩论历史已截断 — 保留了最近 {} 字符 (上限 {})", totalChars, effectiveMax);
        }

        return sb.toString();
    }

    /**
     * 截断单段文本，用于控制 Prompt 长度、加速 AI 响应。
     */
    public String compressText(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...（内容已截断以控制上下文长度）";
    }

    /**
     * 截断其他讨论方方案（批判/收敛轮嵌入用）。
     */
    public String compressPeerResponse(String text) {
        return compressText(text, PEER_RESPONSE_MAX_CHARS);
    }

    /**
     * 截断自身历史方案（审阅/反驳轮引用用）。
     */
    public String compressSelfResponse(String text) {
        return compressText(text, SELF_RESPONSE_MAX_CHARS);
    }

    /**
     * 使用默认上限压缩。
     */
    public String compress(List<DebateRound> history) {
        return compress(history, DEFAULT_MAX_CHARS);
    }

    private String formatRound(DebateRound round) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 第 ").append(round.getRoundNumber()).append(" 轮 (").append(round.getRoundType()).append(") ===\n");
        for (var entry : round.getResponses().entrySet()) {
            AiPlatform platform = entry.getKey();
            ParticipantResponse resp = entry.getValue();
            sb.append("--- ").append(platform.name()).append(" ---\n");
            sb.append(resp.getContent()).append("\n\n");
        }
        return sb.toString();
    }
}
