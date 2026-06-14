package com.debatearena.controller;

import com.debatearena.controller.dto.DebateStartResponse;
import com.debatearena.controller.dto.DebateHistoryItemResponse;
import com.debatearena.controller.dto.DebateStatusResponse;
import com.debatearena.controller.dto.JudgeReportResponse;
import com.debatearena.controller.dto.OutputDocumentItemResponse;
import com.debatearena.controller.exception.DebateSessionNotFoundException;
import com.debatearena.judge.ApiJudgeService;
import com.debatearena.judge.OutputDocumentService;
import com.debatearena.model.*;
import com.debatearena.orchestrator.DebateOrchestrator;
import com.debatearena.persistence.DebateStateStore;
import com.debatearena.reporting.SynthesisGenerator;
import com.debatearena.service.DebateProgressBuilder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 辩论 REST 控制器 —— 提供辩论的启动、查询、报告获取、取消、恢复等端点。
 */
@Slf4j
@RestController
@RequestMapping("/api/debates")
@RequiredArgsConstructor
public class DebateController {

    private final DebateOrchestrator orchestrator;
    private final DebateStateStore stateStore;
    private final SynthesisGenerator synthesisGenerator;
    private final DebateProgressBuilder progressBuilder;
    private final OutputDocumentService outputDocumentService;
    private final ApiJudgeService judgeService;

    /**
     * 启动新辩论。
     *
     * <pre>
     * POST /api/debates
     * Body: { "topic": "...", "maxRounds": 5, "convergenceThreshold": 0.75 }
     * </pre>
     */
    @PostMapping
    public ResponseEntity<DebateStartResponse> startDebate(@Valid @RequestBody DebateRequest request) {
        String sessionId = UUID.randomUUID().toString();
        log.info("📝 收到辩论请求 — session={}, topic={}", sessionId, request.getTopic());

        validateStartRequest(request);
        orchestrator.assertPlatformsReadyForDebate();

        // 异步启动辩论（不阻塞 HTTP 响应）
        orchestrator.startDebate(request, sessionId);

        DebateStartResponse response = DebateStartResponse.builder()
                .sessionId(sessionId)
                .status("RUNNING")
                .message("研讨任务已创建，可在「研讨详情」查看进度")
                .build();

        return ResponseEntity.accepted().body(response);
    }

    /**
     * 查询研讨历史列表。
     *
     * <pre>
     * GET /api/debates?limit=30
     * </pre>
     */
    @GetMapping
    public ResponseEntity<List<DebateHistoryItemResponse>> listDebateHistory(
            @RequestParam(defaultValue = "30") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<DebateHistoryItemResponse> history = stateStore.listLatestSnapshots(safeLimit).stream()
                .map(this::buildHistoryItem)
                .toList();
        return ResponseEntity.ok(history);
    }

    /**
     * 获取全部可勾选的产出文档类型。
     *
     * <pre>
     * GET /api/debates/output-document-types
     * </pre>
     */
    @GetMapping("/output-document-types")
    public ResponseEntity<List<Map<String, String>>> listOutputDocumentTypes() {
        List<Map<String, String>> types = Arrays.stream(OutputDocumentType.values())
                .map(t -> Map.of(
                        "id", t.getId(),
                        "label", t.getLabel(),
                        "description", t.getDescription()))
                .toList();
        return ResponseEntity.ok(types);
    }

    /**
     * 校验启动请求：根据整理方式校验必填项。
     */
    private void validateStartRequest(DebateRequest request) {
        JudgeMode mode = request.getJudgeMode() != null ? request.getJudgeMode() : JudgeMode.API;
        if (mode == JudgeMode.CHANNEL) {
            if (request.getJudgeChannel() == null) {
                throw new IllegalArgumentException("通道整理模式须选择整理通道");
            }
        } else {
            if (request.getJudgeApiKey() == null || request.getJudgeApiKey().isBlank()) {
                throw new IllegalArgumentException("API 整理模式须填写整理服务 API Key");
            }
        }
        outputDocumentService.resolveRequestedTypes(request.getOutputDocuments());
    }

    /**
     * 查询辩论状态。
     *
     * <pre>
     * GET /api/debates/{sessionId}
     * </pre>
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<DebateStatusResponse> getDebateStatus(@PathVariable String sessionId) {
        log.debug("🔍 查询辩论状态 — session={}", sessionId);
        DebateSession session = orchestrator.getSession(sessionId);
        if (session == null) {
            // 尝试从快照加载
            session = stateStore.loadLatestSnapshot(sessionId);
        }
        if (session == null) {
            throw new DebateSessionNotFoundException(sessionId);
        }

        DebateStatusResponse response = buildStatusResponse(session);
        return ResponseEntity.ok(response);
    }

    /**
     * 组装研讨状态与进度步骤。
     */
    private DebateStatusResponse buildStatusResponse(DebateSession session) {
        List<String> participants = session.getParticipatingPlatforms().stream()
                .map(session::getParticipantAlias)
                .toList();
        return DebateStatusResponse.builder()
                .sessionId(session.getSessionId())
                .topic(session.getTopic())
                .status(session.getStatus())
                .statusLabel(progressBuilder.buildStatusLabel(session))
                .currentPhase(progressBuilder.buildCurrentPhase(session))
                .currentRound(session.getCurrentRoundNumber())
                .maxRounds(session.getMaxRounds())
                .convergenceThreshold(session.getConvergenceThreshold())
                .activePlatforms(session.getActivePlatformCount())
                .participants(participants)
                .judgeEnabled(session.isJudgeEnabled())
                .postProcessing(progressBuilder.isPostProcessing(session))
                .failureReason(session.getFailureReason())
                .platformFailures(session.getPlatformFailureSummaries())
                .steps(progressBuilder.buildSteps(session))
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    /**
     * 组装研讨历史列表项。
     */
    private DebateHistoryItemResponse buildHistoryItem(DebateSession session) {
        List<String> participants = session.getParticipatingPlatforms().stream()
                .map(session::getParticipantAlias)
                .toList();
        return DebateHistoryItemResponse.builder()
                .sessionId(session.getSessionId())
                .topic(session.getTopic())
                .status(session.getStatus())
                .statusLabel(progressBuilder.buildStatusLabel(session))
                .currentRound(session.getCurrentRoundNumber())
                .maxRounds(session.getMaxRounds())
                .participants(participants)
                .failureReason(session.getFailureReason())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    /**
     * 获取最终报告。
     *
     * <pre>
     * GET /api/debates/{sessionId}/report
     * Accept: text/markdown
     * </pre>
     */
    @GetMapping(value = "/{sessionId}/report", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public ResponseEntity<String> getReport(@PathVariable String sessionId) {
        log.debug("📄 获取辩论报告 — session={}", sessionId);
        DebateSession session = orchestrator.getSession(sessionId);
        if (session == null) {
            session = stateStore.loadLatestSnapshot(sessionId);
        }
        if (session == null) {
            throw new DebateSessionNotFoundException(sessionId);
        }

        if (session.getStatus() != DebateStatus.CONVERGED
                && session.getStatus() != DebateStatus.MAX_ROUNDS) {
            return ResponseEntity.ok("研讨仍在进行中，当前状态: " + session.getStatus() + "，请稍后再试。");
        }

        OutputDocumentRecord fullDoc = findGeneratedDocument(session, OutputDocumentType.IMPLEMENTATION_PLAN_FULL.getId());
        if (fullDoc != null && fullDoc.isSuccess()) {
            return ResponseEntity.ok(fullDoc.getContent());
        }

        String report = synthesisGenerator.generate(session);
        return ResponseEntity.ok(report);
    }

    /**
     * 列出本场研讨的产出文档及生成状态。
     *
     * <pre>
     * GET /api/debates/{sessionId}/documents
     * </pre>
     */
    @GetMapping("/{sessionId}/documents")
    public ResponseEntity<List<OutputDocumentItemResponse>> listDocuments(@PathVariable String sessionId) {
        DebateSession session = loadSession(sessionId);
        List<OutputDocumentItemResponse> items = new ArrayList<>();
        List<String> requested = session.getOutputDocumentTypes();
        if (requested == null || requested.isEmpty()) {
            return ResponseEntity.ok(items);
        }
        Map<String, OutputDocumentRecord> generated = session.getGeneratedDocuments();
        for (String typeId : requested) {
            String title = typeId;
            String description = "";
            try {
                OutputDocumentType docType = OutputDocumentType.fromId(typeId);
                title = docType.getLabel();
                description = docType.getDescription();
            } catch (IllegalArgumentException ignored) {
            }
            OutputDocumentRecord record = generated != null ? generated.get(typeId) : null;
            String status = "pending";
            String error = null;
            String generatedAt = null;
            if (record != null) {
                status = record.isSuccess() ? "ready" : "failed";
                error = record.getErrorMessage();
                if (record.getGeneratedAt() != null) {
                    generatedAt = record.getGeneratedAt().toString();
                }
            }
            items.add(OutputDocumentItemResponse.builder()
                    .type(typeId)
                    .title(title)
                    .description(description)
                    .status(status)
                    .errorMessage(error)
                    .generatedAt(generatedAt)
                    .build());
        }
        return ResponseEntity.ok(items);
    }

    /**
     * 下载指定类型的产出文档（Markdown）。
     *
     * <pre>
     * GET /api/debates/{sessionId}/documents/{typeId}
     * </pre>
     */
    @GetMapping(value = "/{sessionId}/documents/{typeId}", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public ResponseEntity<String> getDocument(@PathVariable String sessionId, @PathVariable String typeId) {
        DebateSession session = loadSession(sessionId);
        if (session.getStatus() != DebateStatus.CONVERGED
                && session.getStatus() != DebateStatus.MAX_ROUNDS) {
            return ResponseEntity.ok("研讨仍在进行中，文档尚未生成。");
        }
        OutputDocumentRecord record = findGeneratedDocument(session, typeId);
        if (record == null) {
            return ResponseEntity.ok("文档正在生成中，请稍后刷新。");
        }
        if (!record.isSuccess()) {
            return ResponseEntity.ok("文档生成失败：" + record.getErrorMessage());
        }
        return ResponseEntity.ok(record.getContent());
    }

    /**
     * 加载会话（内存或快照）。
     */
    private DebateSession loadSession(String sessionId) {
        DebateSession session = orchestrator.getSession(sessionId);
        if (session == null) {
            session = stateStore.loadLatestSnapshot(sessionId);
        }
        if (session == null) {
            throw new DebateSessionNotFoundException(sessionId);
        }
        return session;
    }

    /**
     * 从会话中查找已生成的产出文档。
     */
    private OutputDocumentRecord findGeneratedDocument(DebateSession session, String typeId) {
        if (session.getGeneratedDocuments() == null) {
            return null;
        }
        return session.getGeneratedDocuments().get(typeId);
    }

    /**
     * 获取裁判整理报告（各轮 prompt/response 及裁判分析）。
     *
     * <pre>
     * GET /api/debates/{sessionId}/judge
     * </pre>
     */
    @GetMapping("/{sessionId}/judge")
    public ResponseEntity<JudgeReportResponse> getJudgeReport(@PathVariable String sessionId) {
        log.debug("⚖️ 获取裁判报告 — session={}", sessionId);
        DebateSession session = loadSession(sessionId);
        return ResponseEntity.ok(buildJudgeReport(session));
    }

    /**
     * 重试指定轮次的整理（此前失败时可调用，需提供 API Key）。
     *
     * <pre>
     * POST /api/debates/{sessionId}/judge/rounds/{roundNumber}/retry
     * Body: { "judgeApiKey": "sk-..." }
     * </pre>
     */
    @PostMapping("/{sessionId}/judge/rounds/{roundNumber}/retry")
    public ResponseEntity<JudgeReportResponse.RoundJudgeDetail> retryRoundJudge(
            @PathVariable String sessionId,
            @PathVariable int roundNumber,
            @RequestBody(required = false) Map<String, String> body) {
        DebateSession session = loadSession(sessionId);
        String apiKey = body != null ? body.get("judgeApiKey") : null;
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("重试整理须填写整理服务 API Key");
        }

        JudgeRoundRecord record = judgeService.retryRoundSummary(session, roundNumber, apiKey);
        DebateRound round = session.getRounds().stream()
                .filter(r -> r.getRoundNumber() == roundNumber)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("轮次不存在: " + roundNumber));
        round.setJudgeRecord(record);
        stateStore.saveSnapshot(sessionId, session.getCurrentRoundNumber(), session);

        Map<String, String> prompts = new LinkedHashMap<>();
        round.getPrompts().forEach((p, text) -> prompts.put(session.getParticipantAlias(p), text));
        Map<String, String> responses = new LinkedHashMap<>();
        round.getResponses().forEach((p, resp) -> {
            if (resp != null && resp.getContent() != null) {
                responses.put(session.getParticipantAlias(p), resp.getContent());
            }
        });
        return ResponseEntity.ok(JudgeReportResponse.RoundJudgeDetail.builder()
                .roundNumber(round.getRoundNumber())
                .roundType(round.getRoundType().name())
                .prompts(prompts)
                .responses(responses)
                .judgeRecord(record)
                .build());
    }

    /**
     * 从会话快照构建裁判报告响应。
     */
    private JudgeReportResponse buildJudgeReport(DebateSession session) {
        List<JudgeReportResponse.RoundJudgeDetail> roundDetails = new ArrayList<>();
        for (DebateRound round : session.getRounds()) {
            Map<String, String> prompts = new LinkedHashMap<>();
            round.getPrompts().forEach((p, text) -> prompts.put(session.getParticipantAlias(p), text));
            Map<String, String> responses = new LinkedHashMap<>();
            round.getResponses().forEach((p, resp) -> {
                if (resp != null && resp.getContent() != null) {
                    responses.put(session.getParticipantAlias(p), resp.getContent());
                }
            });
            roundDetails.add(JudgeReportResponse.RoundJudgeDetail.builder()
                    .roundNumber(round.getRoundNumber())
                    .roundType(round.getRoundType().name())
                    .prompts(prompts)
                    .responses(responses)
                    .judgeRecord(round.getJudgeRecord())
                    .build());
        }
        return JudgeReportResponse.builder()
                .sessionId(session.getSessionId())
                .topic(session.getTopic())
                .judgeEnabled(session.isJudgeEnabled())
                .judgeModel(session.getJudgeModel())
                .rounds(roundDetails)
                .finalJudge(session.getFinalJudgeRecord())
                .build();
    }

    /**
     * 取消正在进行的辩论。
     *
     * <pre>
     * POST /api/debates/{sessionId}/cancel
     * </pre>
     */
    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<DebateStartResponse> cancelDebate(@PathVariable String sessionId) {
        log.info("⏹️ 收到取消辩论请求 — session={}", sessionId);
        orchestrator.cancelDebate(sessionId);
        return ResponseEntity.ok(DebateStartResponse.builder()
                .sessionId(sessionId)
                .status("CANCELLED")
                .message("辩论取消请求已发送，当前轮次完成后将终止")
                .build());
    }

    /**
     * 恢复已中断的辩论（从最近快照恢复）。
     *
     * <pre>
     * POST /api/debates/{sessionId}/resume
     * </pre>
     */
    @PostMapping("/{sessionId}/resume")
    public ResponseEntity<DebateStartResponse> resumeDebate(@PathVariable String sessionId) {
        DebateSession session = stateStore.loadLatestSnapshot(sessionId);
        if (session == null) {
            throw new DebateSessionNotFoundException(sessionId);
        }

        log.info("🔄 恢复辩论 — session={}, fromRound={}", sessionId, session.getCurrentRoundNumber());

        // TODO: 完整的恢复逻辑需要重新初始化 BrowserContext 并从中断点继续
        // 当前版本：返回提示，完整恢复功能在 Phase 2 实现

        return ResponseEntity.ok(DebateStartResponse.builder()
                .sessionId(sessionId)
                .status(session.getStatus().name())
                .message("快照已找到（第 " + session.getCurrentRoundNumber() + " 轮），完整恢复功能将在 Phase 2 实现")
                .build());
    }
}
