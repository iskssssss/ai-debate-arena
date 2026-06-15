package com.debatearena.reporting;

import com.debatearena.model.*;
import com.debatearena.prompts.PromptTemplateService;
import com.debatearena.service.DebateMaterialBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 实现方案报告生成器 —— 收集研讨各轮数据，渲染 final-report.st 模板。
 * <p>
 * 输出供开发团队查阅的需求实现方案文档。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SynthesisGenerator {

    private static final int EXCERPT_LEN = 500;
    private static final int ROUND_EXCERPT_LEN = 800;

    private final PromptTemplateService promptService;
    private final DebateMaterialBuilder materialBuilder;

    /**
     * 根据完整的 DebateSession 生成 Markdown 实现方案报告。
     */
    public String generate(DebateSession session) {
        log.info("📝 开始生成实现方案报告 — session={}, rounds={}, status={}",
                session.getSessionId(), session.getCurrentRoundNumber(), session.getStatus());
        Map<String, Object> params = new HashMap<>();
        params.put("topic", session.getTopic());
        params.put("generated_at", LocalDateTime.now().toString());
        params.put("debate_status", formatStatus(session.getStatus()));
        params.put("total_rounds", String.valueOf(session.getCurrentRoundNumber()));
        params.put("participants", buildParticipantsString(session));
        params.put("debate_summary", buildSummary(session));
        params.put("initial_positions", buildInitialPositions(session));
        params.put("debate_rounds", buildRoundDetails(session));
        params.put("convergence_analysis", buildConvergenceAnalysis(session));
        params.put("final_similarity", formatSimilarity(session));
        params.put("convergence_threshold", String.valueOf(session.getConvergenceThreshold()));
        params.put("convergence_round", buildConvergenceRound(session));
        params.put("final_consensus", buildConsensus(session));
        params.put("persistent_disagreements", buildDisagreements(session));
        params.put("synthesis_recommendation", buildRecommendation(session));
        params.put("decision_matrix", buildDecisionMatrix(session));
        params.put("implementation_advice", buildImplementationAdvice(session));
        params.put("risk_warnings", buildRiskWarnings(session));
        params.put("further_reading", buildFurtherReading(session));

        String report = promptService.renderFinalReport(params);
        log.info("✅ 实现方案报告生成完成 — session={}, 长度={} 字符", session.getSessionId(), report.length());
        return report;
    }

    /**
     * 拼接参与讨论方代号列表。
     */
    private String buildParticipantsString(DebateSession session) {
        List<String> aliases = session.resolveParticipantLabels();
        return aliases.isEmpty() ? "（无）" : String.join("、", aliases);
    }

    /**
     * 生成研讨概述摘要。
     */
    private String buildSummary(DebateSession session) {
        int count = materialBuilder.countParticipants(session);
        StringBuilder sb = new StringBuilder();
        sb.append(count).append(" 位讨论方针对需求「").append(session.getTopic()).append("」");
        sb.append("进行了 ").append(session.getCurrentRoundNumber()).append(" 轮实现方案研讨。");

        if (session.getStatus() == DebateStatus.CONVERGED) {
            sb.append("各方方案已趋于一致，可形成较完整的开发参考文档。");
        } else if (session.getStatus() == DebateStatus.FAILED) {
            sb.append("研讨因异常中断，以下内容为已完成轮次的整理。");
        } else {
            sb.append("研讨在达到最大轮数时仍存在部分分歧，开发团队需结合「未决分歧」章节取舍。");
        }

        if (session.getFinalJudgeRecord() != null && session.getFinalJudgeRecord().isSuccess()) {
            sb.append("\n\n> 已启用裁判整理，「推荐实现方案」章节优先引用裁判最终结论。");
        }
        return sb.toString();
    }

    /**
     * 提取各讨论方首轮初始方案摘要。
     */
    private String buildInitialPositions(DebateSession session) {
        if (session.getRounds().isEmpty()) return "无初始方案记录。";

        DebateRound round1 = session.getRounds().get(0);
        StringBuilder sb = new StringBuilder();

        for (DebateMaterialBuilder.RoundParticipantMaterial item
                : materialBuilder.listRoundMaterials(session, round1)) {
            if (item.response() == null || item.response().getContent() == null) {
                continue;
            }
            sb.append("### ").append(item.label()).append(" 初始方案摘要\n\n");
            sb.append(excerpt(item.response().getContent(), EXCERPT_LEN)).append("\n\n---\n\n");
        }
        return sb.isEmpty() ? "无初始方案记录。" : sb.toString();
    }

    /**
     * 按轮次整理研讨过程与各讨论方回答。
     */
    private String buildRoundDetails(DebateSession session) {
        StringBuilder sb = new StringBuilder();

        for (DebateRound round : session.getRounds()) {
            sb.append("### 第 ").append(round.getRoundNumber()).append(" 轮：")
                    .append(formatRoundType(round.getRoundType())).append("\n\n");

            for (DebateMaterialBuilder.RoundParticipantMaterial item
                    : materialBuilder.listRoundMaterials(session, round)) {
                if (item.response() == null || item.response().getContent() == null) {
                    continue;
                }
                sb.append("#### ").append(item.label()).append("\n\n");
                sb.append(excerpt(item.response().getContent(), ROUND_EXCERPT_LEN)).append("\n\n");
            }

            if (round.getJudgeRecord() != null && round.getJudgeRecord().isSuccess()) {
                sb.append("**裁判整理摘要：**\n")
                        .append(excerpt(round.getJudgeRecord().getAnalysis(), 600))
                        .append("\n\n");
            }

            if (round.getConvergenceResult() != null) {
                ConvergenceResult cr = round.getConvergenceResult();
                sb.append("**方案相似度检测：** minPairwise=")
                        .append(String.format("%.4f", cr.getMinPairwiseSimilarity()))
                        .append(", avgPairwise=")
                        .append(String.format("%.4f", cr.getAverageSimilarity()))
                        .append("\n\n");
            }

            sb.append("---\n\n");
        }
        return sb.toString();
    }

    /**
     * 构建各轮方案相似度分析表。
     */
    private String buildConvergenceAnalysis(DebateSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("| 轮次 | 最小 Pairwise | 平均 Pairwise | 收敛? |\n");
        sb.append("|------|--------------|-------------|------|\n");

        boolean hasData = false;
        for (DebateRound round : session.getRounds()) {
            ConvergenceResult cr = round.getConvergenceResult();
            if (cr != null) {
                hasData = true;
                sb.append("| 第 ").append(round.getRoundNumber()).append(" 轮 | ")
                        .append(String.format("%.4f", cr.getMinPairwiseSimilarity())).append(" | ")
                        .append(String.format("%.4f", cr.getAverageSimilarity())).append(" | ")
                        .append(cr.isConverged() ? "✅" : "❌").append(" |\n");
            }
        }
        return hasData ? sb.toString() : "暂无收敛检测数据。";
    }

    private String formatSimilarity(DebateSession session) {
        DebateRound last = session.getLatestRound();
        if (last == null || last.getConvergenceResult() == null) return "N/A";
        return String.format("%.4f", last.getConvergenceResult().getAverageSimilarity());
    }

    private String buildConvergenceRound(DebateSession session) {
        for (DebateRound round : session.getRounds()) {
            if (round.getConvergenceResult() != null && round.getConvergenceResult().isConverged()) {
                return "第 " + round.getRoundNumber() + " 轮";
            }
        }
        return "未收敛";
    }

    /**
     * 从收敛轮或裁判结论提取已达成共识内容。
     */
    private String buildConsensus(DebateSession session) {
        if (session.getFinalJudgeRecord() != null && session.getFinalJudgeRecord().isSuccess()) {
            return "详见「推荐实现方案」中的裁判最终整理。\n\n"
                    + excerpt(extractSection(session.getFinalJudgeRecord().getAnalysis(), "已达成共识"), 1500);
        }
        return extractConvergenceSection(session, "已达成一致的设计", "尚未从收敛轮提取到明确共识，请查阅研讨过程各轮内容。");
    }

    /**
     * 提取未决分歧内容。
     */
    private String buildDisagreements(DebateSession session) {
        if (session.getFinalJudgeRecord() != null && session.getFinalJudgeRecord().isSuccess()) {
            return excerpt(extractSection(session.getFinalJudgeRecord().getAnalysis(), "未决分歧"), 1500);
        }
        return extractConvergenceSection(session, "仍存分歧的设计", "未提取到明确分歧记录，请查阅各讨论方最终方案对比。");
    }

    /**
     * 生成推荐实现方案：优先使用裁判最终报告，否则汇总收敛轮输出。
     */
    private String buildRecommendation(DebateSession session) {
        if (session.getFinalJudgeRecord() != null && session.getFinalJudgeRecord().isSuccess()) {
            return session.getFinalJudgeRecord().getAnalysis();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("本场未启用裁判或裁判报告不可用。以下为各讨论方收敛轮「推荐实现方案」摘录：\n\n");
        appendConvergenceResponses(session, sb, "推荐实现方案");
        if (sb.toString().endsWith("：\n\n")) {
            appendLatestResponses(session, sb);
        }
        return sb.toString();
    }

    /**
     * 构建开发决策参考矩阵（有裁判时引用，否则提示查阅上文）。
     */
    private String buildDecisionMatrix(DebateSession session) {
        if (session.getFinalJudgeRecord() != null && session.getFinalJudgeRecord().isSuccess()) {
            String techStack = extractSection(session.getFinalJudgeRecord().getAnalysis(), "技术栈");
            if (!techStack.isBlank()) {
                return techStack;
            }
        }
        return "技术选型与架构决策详见上方「推荐实现方案」及各讨论方初始/收敛轮输出。";
    }

    /**
     * 汇总开发实施建议。
     */
    private String buildImplementationAdvice(DebateSession session) {
        if (session.getFinalJudgeRecord() != null && session.getFinalJudgeRecord().isSuccess()) {
            String advice = extractSection(session.getFinalJudgeRecord().getAnalysis(), "开发注意事项");
            if (!advice.isBlank()) return advice;
            String plan = extractSection(session.getFinalJudgeRecord().getAnalysis(), "分阶段实施");
            if (!plan.isBlank()) return plan;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("综合各讨论方收敛轮「开发注意事项」与「实施计划」：\n\n");
        appendConvergenceResponses(session, sb, "开发注意事项");
        return sb.toString();
    }

    /**
     * 汇总风险提示。
     */
    private String buildRiskWarnings(DebateSession session) {
        if (session.getFinalJudgeRecord() != null && session.getFinalJudgeRecord().isSuccess()) {
            String risks = extractSection(session.getFinalJudgeRecord().getAnalysis(), "风险");
            if (!risks.isBlank()) return risks;
        }
        StringBuilder sb = new StringBuilder();
        appendConvergenceResponses(session, sb, "风险");
        String result = sb.toString();
        return result.endsWith("：\n\n")
                ? "请查阅各讨论方方案中的「风险与缓解」章节。"
                : result;
    }

    private String buildFurtherReading(DebateSession session) {
        return "- 完整研讨记录与裁判整理请通过 Session ID 在系统中查询\n"
                + "- 开发前建议组织技术评审，对推荐方案中的待确认事项逐项闭环";
    }

    /**
     * 从收敛轮各讨论方回答中提取指定章节附近的内容。
     */
    private String extractConvergenceSection(DebateSession session, String keyword, String fallback) {
        DebateRound convergenceRound = findLastRoundOfType(session, RoundType.CONVERGENCE);
        if (convergenceRound == null) return fallback;

        StringBuilder sb = new StringBuilder();
        for (DebateMaterialBuilder.RoundParticipantMaterial item
                : materialBuilder.listRoundMaterials(session, convergenceRound)) {
            if (item.response() == null || item.response().getContent() == null) {
                continue;
            }
            String section = extractSection(item.response().getContent(), keyword);
            if (!section.isBlank()) {
                sb.append("**").append(item.label()).append("：**\n");
                sb.append(section).append("\n\n");
            }
        }
        return sb.isEmpty() ? fallback : sb.toString().trim();
    }

    /**
     * 追加收敛轮中包含指定关键词的讨论方回答摘录。
     */
    private void appendConvergenceResponses(DebateSession session, StringBuilder sb, String keyword) {
        DebateRound convergenceRound = findLastRoundOfType(session, RoundType.CONVERGENCE);
        if (convergenceRound == null) {
            sb.append("（无收敛轮记录）");
            return;
        }
        sb.append("### ").append(keyword).append("\n\n");
        for (DebateMaterialBuilder.RoundParticipantMaterial item
                : materialBuilder.listRoundMaterials(session, convergenceRound)) {
            if (item.response() == null || item.response().getContent() == null) {
                continue;
            }
            sb.append("#### ").append(item.label()).append("\n\n");
            sb.append(excerpt(item.response().getContent(), ROUND_EXCERPT_LEN)).append("\n\n");
        }
    }

    /**
     * 无收敛轮时，使用各讨论方最新回答作为兜底。
     */
    private void appendLatestResponses(DebateSession session, StringBuilder sb) {
        for (String label : session.resolveParticipantLabels()) {
            String text = materialBuilder.getLatestResponseText(session, label);
            if (text == null || text.isBlank()) {
                continue;
            }
            sb.append("#### ").append(label).append(" 最新方案\n\n");
            sb.append(excerpt(text, ROUND_EXCERPT_LEN)).append("\n\n");
        }
    }

    private DebateRound findLastRoundOfType(DebateSession session, RoundType type) {
        for (int i = session.getRounds().size() - 1; i >= 0; i--) {
            if (session.getRounds().get(i).getRoundType() == type) {
                return session.getRounds().get(i);
            }
        }
        return null;
    }

    /**
     * 从 Markdown 文本中提取包含关键词的段落（关键词起至下一同级标题止）。
     */
    private String extractSection(String content, String keyword) {
        if (content == null || keyword == null) return "";
        int idx = content.indexOf(keyword);
        if (idx < 0) return "";
        int start = content.lastIndexOf('\n', idx);
        start = start < 0 ? idx : start + 1;
        int nextHeading = content.indexOf("\n## ", start + keyword.length());
        if (nextHeading < 0) nextHeading = content.indexOf("\n### ", start + keyword.length());
        String section = nextHeading > 0 ? content.substring(start, nextHeading) : content.substring(start);
        return section.trim();
    }

    private String excerpt(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n\n*（内容过长已截断，完整内容请查快照或裁判整理）*";
    }

    private String formatStatus(DebateStatus status) {
        return switch (status) {
            case CONVERGED -> "已收敛";
            case MAX_ROUNDS -> "达最大轮数（部分分歧）";
            case FAILED -> "失败";
            case CREATED, RUNNING, INITIAL_ANSWER, CRITIQUE, REBUTTAL, CONSENSUS -> "进行中";
        };
    }

    private String formatRoundType(RoundType type) {
        return switch (type) {
            case INITIAL -> "初始方案设计";
            case CRITIQUE -> "方案交叉审阅";
            case REBUTTAL -> "方案修订回应";
            case CONVERGENCE -> "收敛与最终输出";
        };
    }
}
