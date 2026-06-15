package com.debatearena.judge;

import com.debatearena.model.*;
import com.debatearena.persistence.DebateStateStore;
import com.debatearena.service.DebateMaterialBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 研讨产出文档服务 —— 研讨结束后按用户勾选类型，逐项调用整理服务生成完整独立文档。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutputDocumentService {

    private final DebateStateStore stateStore;
    private final DebateMaterialBuilder materialBuilder;

    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    /**
     * 为会话生成全部勾选的产出文档，并写入会话状态。
     */
    public void generateRequestedDocuments(DebateSession session, JudgeService judgeService) {
        List<String> typeIds = session.getOutputDocumentTypes();
        if (typeIds == null || typeIds.isEmpty()) {
            return;
        }
        if (judgeService == null || !judgeService.isJudgeEnabled(session)) {
            log.warn("整理服务未就绪，跳过产出文档生成 — session={}", session.getSessionId());
            markAllFailed(session, typeIds, "整理服务未就绪");
            return;
        }

        Map<String, OutputDocumentRecord> documents = new LinkedHashMap<>();
        for (String typeId : typeIds) {
            OutputDocumentType type;
            try {
                type = OutputDocumentType.fromId(typeId);
            } catch (IllegalArgumentException e) {
                documents.put(typeId, OutputDocumentRecord.failure(typeId, typeId, e.getMessage()));
                continue;
            }
            OutputDocumentRecord record = generateOne(session, type, judgeService);
            documents.put(type.getId(), record);
            stateStoreSaveProgress(session, documents);
        }
        session.setGeneratedDocuments(documents);
        log.info("📚 产出文档生成完成 — session={}, count={}", session.getSessionId(), documents.size());
    }

    /**
     * 生成单份产出文档。
     */
    public OutputDocumentRecord generateOne(DebateSession session, OutputDocumentType type, JudgeService judgeService) {
        try {
            String systemPrompt = buildSystemPrompt(type.getTemplatePath());
            String userPrompt = buildDebateContext(session);
            String raw = judgeService.generateDocumentContent(session, systemPrompt, userPrompt);
            String content = DocumentContentSanitizer.sanitize(raw);
            content = enforceStatusConsistency(content, session);
            log.info("📄 产出文档已生成 — session={}, type={}, len={}",
                    session.getSessionId(), type.getId(), content.length());
            return OutputDocumentRecord.success(type.getId(), type.getLabel(), content);
        } catch (Exception e) {
            log.error("产出文档生成失败 — session={}, type={}: {}",
                    session.getSessionId(), type.getId(), e.getMessage());
            return OutputDocumentRecord.failure(type.getId(), type.getLabel(), e.getMessage());
        }
    }

    /**
     * 解析用户请求的文档类型列表；空列表时返回系统默认。
     */
    public List<String> resolveRequestedTypes(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return OutputDocumentType.defaultIds();
        }
        return requested.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .peek(OutputDocumentType::fromId)
                .toList();
    }

    /**
     * 构建送入 Judge 的完整研讨上下文。
     */
    private String buildDebateContext(DebateSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 需求描述 ===\n").append(session.getTopic()).append("\n\n");
        sb.append("=== 研讨元信息 ===\n");
        sb.append("status（枚举，文档信息区须与此一致）: ").append(session.getStatus()).append("\n");
        sb.append("最终状态中文（文档信息区必须照抄此行）: ")
                .append(formatFinalStatusLabel(session)).append("\n");
        sb.append("是否已收敛: ").append(session.getStatus() == DebateStatus.CONVERGED ? "是" : "否")
                .append("\n");
        sb.append("总轮数: ").append(session.getCurrentRoundNumber()).append("\n");
        sb.append("收敛阈值: ").append(session.getConvergenceThreshold()).append("\n");
        appendLastConvergenceMetrics(sb, session);
        if (session.getStatus() != DebateStatus.CONVERGED) {
            sb.append("【重要】本场研讨未收敛，禁止写「已收敛」「最终状态已收敛」等表述。\n");
        }
        materialBuilder.appendParticipantLabelsLine(sb, session);
        sb.append("\n");

        for (DebateRound round : session.getRounds()) {
            sb.append("\n######## 第 ").append(round.getRoundNumber())
                    .append(" 轮 (").append(round.getRoundType()).append(") ########\n");

            if (round.getConvergenceResult() != null) {
                ConvergenceResult c = round.getConvergenceResult();
                sb.append("[收敛检测] minPairwise=")
                        .append(String.format("%.4f", c.getMinPairwiseSimilarity()))
                        .append(", converged=").append(c.isConverged()).append("\n");
            }

            if (round.getJudgeRecord() != null && round.getJudgeRecord().isSuccess()) {
                sb.append("[本轮整理摘要]\n")
                        .append(truncate(round.getJudgeRecord().getAnalysis(), 4000))
                        .append("\n");
            }

            materialBuilder.appendRoundMaterials(sb, session, round, 8000, 12000);
        }
        return sb.toString();
    }

    /**
     * 将全部文档标记为失败（用于缺少 API Key 等场景）。
     */
    private void markAllFailed(DebateSession session, List<String> typeIds, String reason) {
        Map<String, OutputDocumentRecord> documents = new LinkedHashMap<>();
        for (String typeId : typeIds) {
            String title = typeId;
            try {
                title = OutputDocumentType.fromId(typeId).getLabel();
            } catch (IllegalArgumentException ignored) {
            }
            documents.put(typeId, OutputDocumentRecord.failure(typeId, title, reason));
        }
        session.setGeneratedDocuments(documents);
    }

    /**
     * 每生成一份文档后持久化快照，便于前端轮询查看进度。
     */
    private void stateStoreSaveProgress(DebateSession session, Map<String, OutputDocumentRecord> documents) {
        session.setGeneratedDocuments(new LinkedHashMap<>(documents));
        stateStore.saveSnapshot(session.getSessionId(), session.getCurrentRoundNumber(), session);
    }

    /**
     * 将文档中错误的「已收敛」表述修正为与会话真实状态一致。
     */
    private String enforceStatusConsistency(String content, DebateSession session) {
        if (content == null || content.isBlank() || session.getStatus() == null) {
            return content;
        }
        if (session.getStatus() == DebateStatus.CONVERGED) {
            return content;
        }
        String correct = formatFinalStatusLabel(session);
        String fixed = content;
        fixed = fixed.replaceAll("(?m)(最终状态[：:]\\s*)已收敛", "$1" + correct);
        fixed = fixed.replaceAll("(?m)(研讨状态[：:]\\s*)已收敛", "$1" + correct);
        fixed = fixed.replace("最终状态已收敛", correct);
        return fixed;
    }

    /**
     * 将会话状态映射为文档信息区应使用的中文标签。
     */
    private String formatFinalStatusLabel(DebateSession session) {
        return switch (session.getStatus()) {
            case CONVERGED -> "已收敛";
            case MAX_ROUNDS -> "已达轮次上限（未收敛）";
            case FAILED -> "研讨失败";
            default -> session.getStatus().name();
        };
    }

    /**
     * 附加最后一轮收敛检测指标，供文档准确描述未收敛程度。
     */
    private void appendLastConvergenceMetrics(StringBuilder sb, DebateSession session) {
        if (session.getRounds() == null || session.getRounds().isEmpty()) {
            return;
        }
        DebateRound last = session.getRounds().get(session.getRounds().size() - 1);
        ConvergenceResult c = last.getConvergenceResult();
        if (c == null) {
            return;
        }
        sb.append("末轮收敛检测: minPairwise=")
                .append(String.format("%.4f", c.getMinPairwiseSimilarity()))
                .append(", converged=").append(c.isConverged()).append("\n");
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "\n... (材料过长已截断)";
    }

    /**
     * 组装系统提示词：文档类型模板 + 统一输出格式约束。
     */
    private String buildSystemPrompt(String templatePath) {
        return loadTemplate(templatePath) + loadTemplate("templates/output-documents/_output-format-rules.txt");
    }

    /**
     * 加载并缓存文档类型的 Judge 系统提示词模板。
     */
    private String loadTemplate(String path) {
        return templateCache.computeIfAbsent(path, p -> {
            try {
                ClassPathResource resource = new ClassPathResource(p);
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("无法加载产出文档模板: " + p, e);
            }
        });
    }
}
