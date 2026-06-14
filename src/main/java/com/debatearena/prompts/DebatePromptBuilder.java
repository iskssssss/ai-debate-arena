package com.debatearena.prompts;

import com.debatearena.model.AiPlatform;
import com.debatearena.model.DebateRound;
import com.debatearena.model.DebateSession;
import com.debatearena.model.ParticipantResponse;
import com.debatearena.model.RoundType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 辩论 Prompt 构建器 —— 根据实际参与方动态生成需求实现方案研讨提示词，使用「讨论方甲/乙/丙」匿名代号。
 */
@Service
@RequiredArgsConstructor
public class DebatePromptBuilder {

    private static final String[] ALIAS_SUFFIXES = {"甲", "乙", "丙", "丁", "戊"};

    private final PromptTemplateService templateService;
    private final CompressionService compressionService;

    /**
     * 在排除未参与平台后，为活跃讨论方分配「讨论方甲/乙/丙」代号。
     */
    public void assignParticipantAliases(DebateSession session) {
        session.getParticipatingPlatforms().clear();
        session.getParticipantAliases().clear();
        int index = 0;
        for (AiPlatform platform : AiPlatform.values()) {
            if (!session.isPlatformActive(platform)) {
                continue;
            }
            String alias = "讨论方" + ALIAS_SUFFIXES[Math.min(index, ALIAS_SUFFIXES.length - 1)];
            session.getParticipatingPlatforms().add(platform);
            session.getParticipantAliases().put(platform, alias);
            index++;
        }
    }

    /**
     * 构建第 1 轮初始回答 Prompt。
     */
    public String buildInitialPrompt(DebateSession session, AiPlatform self) {
        return templateService.renderInitialPrompt(
                session.getTopic(),
                session.getParticipantAlias(self),
                session.getParticipatingPlatforms().size());
    }

    /**
     * 构建批判轮 Prompt（仅包含实际参与的其他讨论方）。
     */
    public String buildCritiquePrompt(DebateSession session, AiPlatform critic) {
        List<AiPlatform> others = session.getOtherActivePlatforms(critic);
        String otherSection = buildOtherDebatersSection(session, others);
        String critiqueInstructions = buildCritiqueInstructions(others, session);
        return templateService.renderCritiquePrompt(
                session.getTopic(),
                session.getParticipantAlias(critic),
                session.getParticipatingPlatforms().size(),
                otherSection,
                compressionService.compressSelfResponse(session.getLatestResponseText(critic)),
                critiqueInstructions);
    }

    /**
     * 构建反驳轮 Prompt。
     */
    public String buildRebuttalPrompt(DebateSession session, AiPlatform self, String critiquesText) {
        return templateService.renderRebuttalPrompt(
                session.getTopic(),
                session.getParticipantAlias(self),
                compressionService.compressSelfResponse(session.getLatestResponseText(self)),
                compressionService.compressText(critiquesText, 3000));
    }

    /**
     * 构建收敛确认 Prompt。
     */
    public String buildConvergencePrompt(DebateSession session, AiPlatform self) {
        return templateService.renderConvergencePrompt(
                session.getTopic(),
                session.getParticipantAlias(self),
                compressionService.compressSelfResponse(session.getLatestResponseText(self)),
                buildOtherPositionsSection(session, self));
    }

    /**
     * 格式化其他讨论方最新立场（用于收敛轮）。
     */
    public String buildOtherPositionsSection(DebateSession session, AiPlatform exclude) {
        StringBuilder sb = new StringBuilder();
        for (AiPlatform platform : session.getOtherActivePlatforms(exclude)) {
            String text = session.getLatestResponseText(platform);
            if (text == null || text.isBlank()) continue;
            sb.append("=== ").append(session.getParticipantAlias(platform)).append(" ===\n");
            sb.append(compressionService.compressPeerResponse(text)).append("\n\n");
        }
        return sb.isEmpty() ? "（暂无其他讨论方方案）" : sb.toString().trim();
    }

    /**
     * 构建反驳轮所需的「其他讨论方批判」文本。
     */
    public String buildCritiquesForRebuttal(DebateSession session, AiPlatform target) {
        StringBuilder sb = new StringBuilder();
        for (AiPlatform other : session.getOtherActivePlatforms(target)) {
            ParticipantResponse critique = findLatestCritiqueResponse(session, other);
            if (critique != null && critique.getContent() != null && !critique.getContent().isBlank()) {
                sb.append("--- ").append(session.getParticipantAlias(other)).append(" 的审阅意见 ---\n");
                sb.append(compressionService.compressPeerResponse(critique.getContent())).append("\n\n");
            }
        }
        return sb.isEmpty() ? "（尚无其他讨论方对您的审阅意见）" : sb.toString().trim();
    }

    /**
     * 查找指定讨论方在最近一轮批判中的发言。
     */
    private ParticipantResponse findLatestCritiqueResponse(DebateSession session, AiPlatform from) {
        for (int i = session.getRounds().size() - 1; i >= 0; i--) {
            var round = session.getRounds().get(i);
            if (round.getRoundType() == RoundType.CRITIQUE) {
                return round.getResponse(from);
            }
        }
        return null;
    }

    /**
     * 拼接其他讨论方观点区块。
     */
    private String buildOtherDebatersSection(DebateSession session, List<AiPlatform> others) {
        StringBuilder sb = new StringBuilder();
        for (AiPlatform platform : others) {
            String alias = session.getParticipantAlias(platform);
            String response = session.getLatestResponseText(platform);
            sb.append("=== ").append(alias).append(" 的方案 ===\n");
            sb.append(response != null && !response.isBlank()
                    ? compressionService.compressPeerResponse(response) : "（暂无回答）");
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 按实际其他讨论方数量生成结构化审阅任务说明，覆盖覆盖度、架构、选型、落地性与风险等维度。
     */
    private String buildCritiqueInstructions(List<AiPlatform> others, DebateSession session) {
        StringBuilder sb = new StringBuilder();
        for (AiPlatform platform : others) {
            String alias = session.getParticipantAlias(platform);
            sb.append("## 对 ").append(alias).append(" 方案的审阅\n\n");
            sb.append("### 需求覆盖度\n");
            sb.append("- 是否遗漏关键功能、边界场景或非功能需求（性能/安全/可用性）？\n");
            sb.append("- 每条遗漏须指出具体缺口及建议补充方式。\n\n");
            sb.append("### 架构合理性\n");
            sb.append("- 模块划分、依赖方向、扩展点是否合理？\n");
            sb.append("- 是否存在单点瓶颈、循环依赖或职责重叠？给出具体调整建议。\n\n");
            sb.append("### 技术选型\n");
            sb.append("- 推荐技术是否匹配场景规模与团队能力？\n");
            sb.append("- 有无更合适的替代方案？说明替换成本与收益。\n\n");
            sb.append("### 接口与数据设计\n");
            sb.append("- 核心接口/数据模型是否完整、可演进？\n");
            sb.append("- 状态流转、幂等、一致性策略是否明确？指出缺陷与修订建议。\n\n");
            sb.append("### 可落地性\n");
            sb.append("- 实施难度、工期估算、分阶段路径是否可信？\n");
            sb.append("- 哪些任务依赖外部系统/人力/调研？如何降低不确定性？\n\n");
            sb.append("### 风险盲区\n");
            sb.append("- ").append(alias).append(" 忽略了哪些上线、运维、安全、合规或回滚风险？\n");
            sb.append("- 每条风险附缓解措施建议。\n\n");
            sb.append("### 质量评价\n");
            sb.append("（强 / 中 / 弱 — 附 1～2 句理由，侧重开发团队直接查阅与落地的价值）\n\n");
            sb.append("---\n\n");
        }
        sb.append("## 交叉吸收与己方修订\n\n");
        sb.append("综合审阅其他讨论方方案后：\n");
        sb.append("- 您的方案哪些部分需要调整？列出具体模块/接口/流程及调整理由。\n");
        sb.append("- 其他方案中哪些设计值得吸收？如何融入您的方案？\n");
        sb.append("- 给出您当前最推荐的实现路径（3～8 条要点，供开发参考）。\n");
        return sb.toString().trim();
    }
}
