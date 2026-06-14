package com.debatearena.judge;

import com.debatearena.model.*;
import com.debatearena.persistence.DebateStateStore;
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
        sb.append("状态: ").append(session.getStatus()).append("\n");
        sb.append("总轮数: ").append(session.getCurrentRoundNumber()).append("\n");
        sb.append("收敛阈值: ").append(session.getConvergenceThreshold()).append("\n");
        sb.append("参与讨论方: ");
        session.getParticipatingPlatforms().forEach(p ->
                sb.append(session.getParticipantAlias(p)).append(" "));
        sb.append("\n\n");

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

            for (AiPlatform platform : session.getParticipatingPlatforms()) {
                String alias = session.getParticipantAlias(platform);
                String prompt = round.getPrompts().get(platform);
                ParticipantResponse response = round.getResponse(platform);
                if (prompt == null && response == null) {
                    continue;
                }
                sb.append("\n--- ").append(alias).append(" ---\n");
                if (prompt != null) {
                    sb.append("[发送的提示词]\n").append(truncate(prompt, 8000)).append("\n\n");
                }
                if (response != null && response.getContent() != null) {
                    sb.append("[收到的回答]\n").append(truncate(response.getContent(), 12000)).append("\n");
                }
            }
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
