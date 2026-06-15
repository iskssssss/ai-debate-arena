package com.debatearena.orchestrator;

import com.debatearena.adapter.BrowserAutomationException;
import com.debatearena.adapter.PlatformAdapter;
import com.debatearena.adapter.ResponseContentValidator;
import com.debatearena.browser.LoginStatus;
import com.debatearena.browser.PlaywrightManager;
import com.debatearena.browser.ProfileManager;
import com.debatearena.browser.SelectorProvider;
import com.debatearena.browser.YamlSelectorProvider;
import com.debatearena.config.AiPlatformProperties;
import com.debatearena.config.DebateConfig;
import com.debatearena.controller.exception.InsufficientPlatformsException;
import com.debatearena.convergence.ConvergenceDetector;
import com.debatearena.judge.ApiJudgeService;
import com.debatearena.judge.JudgeService;
import com.debatearena.judge.OutputDocumentService;
import com.debatearena.model.*;
import com.debatearena.persistence.DebateStateStore;
import com.debatearena.model.ParticipantResponse;
import com.debatearena.model.RoundType;
import com.debatearena.prompts.DebatePromptBuilder;
import com.debatearena.prompts.PromptTemplateService;
import com.debatearena.reporting.SynthesisGenerator;
import com.debatearena.service.ApiParticipantService;
import com.debatearena.service.ChannelRegistryService;
import com.debatearena.service.ParticipantSelectionHelper;
import com.debatearena.service.ThirdPartyApiSettingsService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 辩论编排器 —— 核心引擎，管理辩论全生命周期。
 * <p>
 * 状态机流程：
 * <pre>
 * INITIAL_ANSWER → CRITIQUE → REBUTTAL → (收敛检查) → CONVERGED / MAX_ROUNDS
 *                                      ↖ 未收敛继续循环 ↙
 * </pre>
 * <p>
 * 关键设计：
 * <ul>
 *   <li>三平台并行交互（每轮同时向三个 AI 发送 Prompt）</li>
 *   <li>硬限制：最大 6 轮 + 最大 20 分钟</li>
 *   <li>单平台故障优雅降级（最少 2 个 AI 可继续）</li>
 *   <li>每轮结束后 JSON 快照持久化，支持崩溃恢复</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DebateOrchestrator {

    private final DebateConfig debateConfig;
    private final AiPlatformProperties platformProperties;
    private final PlaywrightManager playwrightManager;
    private final ProfileManager profileManager;
    private final ConversationManager conversationManager;
    private final ConvergenceDetector convergenceDetector;
    private final PromptTemplateService promptService;
    private final DebatePromptBuilder promptBuilder;
    private final DebateStateStore stateStore;
    private final SynthesisGenerator synthesisGenerator;
    private final ApiJudgeService apiJudgeService;
    private final ThirdPartyApiSettingsService apiSettingsService;
    private final ChannelRegistryService channelRegistryService;
    private final ApiParticipantService apiParticipantService;
    private final OutputDocumentService outputDocumentService;

    /** 注入两种整理服务实现（API 与通道），按 bean name 路由。 */
    @Resource
    private Map<String, JudgeService> judgeServices;
    private final PlatformQueueManager platformQueueManager;

    @Resource(name = "debateExecutor")
    private Executor debateExecutor;

    /** 注入所有平台适配器（Spring 自动收集）。 */
    @Resource
    private Map<AiPlatform, PlatformAdapter> adapters;

    /** 最大辩论时长（毫秒）—— 20 分钟硬限制。 */
    private static final long MAX_DEBATE_TIME_MS = 20 * 60 * 1000;

    /** 活跃辩论会话缓存。 */
    private final ConcurrentMap<String, DebateSession> sessionCache = new ConcurrentHashMap<>();

    /** 取消标志映射。 */
    private final ConcurrentMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("🎯 辩论编排器已就绪 — maxRounds={}, convergenceThreshold={}, aiTimeout={}s, parallel={}",
                debateConfig.getMaxRounds(), debateConfig.getConvergenceThreshold(),
                debateConfig.getAiTimeoutSeconds(), debateConfig.isParallelExecution());
    }

    /**
     * 异步启动辩论（通过 REST API 触发）。
     */
    @Async("debateExecutor")
    public void startDebate(DebateRequest request, String sessionId) {
        cancelFlags.put(sessionId, new AtomicBoolean(false));
        DebateSession session = initSession(request, sessionId);

        // 注册整理服务（API 模式存 Key，通道模式初始化浏览器页面）
        JudgeService activeJudge = resolveJudge(session);
        if (request.getJudgeMode() == JudgeMode.API) {
            String apiKey = apiSettingsService.resolveApiKey(request.getJudgeApiKey());
            if (apiKey != null && !apiKey.isBlank()) {
                apiJudgeService.registerApiKey(sessionId, apiKey);
            }
        }
        activeJudge.registerSession(sessionId, session);
        sessionCache.put(sessionId, session);

        log.info("🚀 辩论开始 — session={}, topic={}, maxRounds={}",
                sessionId, session.getTopic(), session.getMaxRounds());

        try {
            // --- 排除未登录 / 未就绪平台 ---
            excludeUnreadyChannels(session);
            promptBuilder.assignParticipantAliases(session);
            log.info("讨论方代号: {}", session.getChannelAliases());

            // --- 初始化浏览器和适配器 ---
            initializeAdapters(session);
            stateStore.saveSnapshot(sessionId, 0, session);

            // --- 第 1 轮：初始回答 ---
            session.setStatus(DebateStatus.INITIAL_ANSWER);
            DebateRound round1 = executeRound(session, 1, RoundType.INITIAL);
            session.addRound(round1);
            checkAndSetConvergence(session, round1);
            runJudgeForRound(session, round1);
            stateStore.saveSnapshot(sessionId, 1, session);

            if (round1.getConvergenceResult() != null && round1.getConvergenceResult().isConverged()) {
                finishDebate(session, DebateStatus.CONVERGED);
                return;
            }

            // --- 第 2 轮：交叉批判 ---
            session.setStatus(DebateStatus.CRITIQUE);
            DebateRound round2 = executeCritiqueRound(session, 2);
            session.addRound(round2);
            checkAndSetConvergence(session, round2);
            runJudgeForRound(session, round2);
            stateStore.saveSnapshot(sessionId, 2, session);

            if (round2.getConvergenceResult() != null && round2.getConvergenceResult().isConverged()) {
                finishDebate(session, DebateStatus.CONVERGED);
                return;
            }

            // --- 第 3+ 轮：反驳循环 ---
            for (int r = 3; r <= session.getMaxRounds(); r++) {
                if (isCancelled(sessionId)) {
                    log.info("⏹️ 辩论已取消 — session={}", sessionId);
                    return;
                }

                session.setStatus(DebateStatus.REBUTTAL);
                DebateRound round = executeRebuttalRound(session, r);
                session.addRound(round);
                checkAndSetConvergence(session, round);
                runJudgeForRound(session, round);
                stateStore.saveSnapshot(sessionId, r, session);

                if (round.getConvergenceResult() != null && round.getConvergenceResult().isConverged()) {
                    finishDebate(session, DebateStatus.CONVERGED);
                    return;
                }

                // 硬时间限制检查
                if (elapsedMs(session) > MAX_DEBATE_TIME_MS) {
                    log.warn("⏰ 辩论超时（{}ms）— 强制终止", MAX_DEBATE_TIME_MS);
                    finishDebate(session, DebateStatus.MAX_ROUNDS);
                    return;
                }
            }

            finishDebate(session, DebateStatus.MAX_ROUNDS);

        } catch (DebateFailedException e) {
            log.error("💥 辩论失败 — session={}: {}", sessionId, e.getMessage());
            session.setStatus(DebateStatus.FAILED);
            if (session.getFailureReason() == null || session.getFailureReason().isBlank()) {
                session.markTerminalFailure(e.getMessage());
            }
            stateStore.saveSnapshot(sessionId, session.getCurrentRoundNumber(), session);
        } catch (Exception e) {
            log.error("💥 辩论异常 — session={}: {}", sessionId, e.getMessage(), e);
            session.setStatus(DebateStatus.FAILED);
            session.markTerminalFailure(e.getMessage() != null ? e.getMessage() : "未知异常");
            if (session.getFailedAtRound() == 0 && session.getCurrentRoundNumber() > 0) {
                session.setFailedAtRound(session.getCurrentRoundNumber());
            }
            stateStore.saveSnapshot(sessionId, session.getCurrentRoundNumber(), session);
        } finally {
            cleanupAdapters();
            // 已收敛/达上限的会话由异步赛后任务清理
            DebateStatus finalStatus = session.getStatus();
            if (finalStatus != DebateStatus.CONVERGED && finalStatus != DebateStatus.MAX_ROUNDS) {
                resolveJudge(session).cleanup(sessionId);
                sessionCache.remove(sessionId);
                cancelFlags.remove(sessionId);
            }
        }
    }

    /**
     * 在平台专用单线程队列中提交 Prompt，worker 异常时自动重置浏览器并重试一次。
     */
    private CompletableFuture<String> submitSendPrompt(PlatformAdapter adapter, AiPlatform platform, String prompt) {
        return platformQueueManager.submit(platform, () -> executeSendWithRetry(adapter, platform, prompt));
    }

    /**
     * 执行 Prompt 发送，Playwright worker 断开时尝试恢复浏览器后重试。
     */
    private String executeSendWithRetry(PlatformAdapter adapter, AiPlatform platform, String prompt) {
        try {
            return adapter.sendPrompt(prompt).get();
        } catch (Exception first) {
            if (!isPlaywrightWorkerError(first)) {
                throw new RuntimeException(first);
            }
            log.warn("{} Playwright worker 异常，重置浏览器后重试…", platform.name());
            recoverPlatformBrowser(platform, adapter);
            try {
                return adapter.sendPrompt(prompt).get();
            } catch (Exception retry) {
                throw new RuntimeException(retry);
            }
        }
    }

    /**
     * 判断是否为 Playwright worker 连接断开类异常。
     */
    private boolean isPlaywrightWorkerError(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("Object doesn't exist") || msg.contains("worker@"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * 关闭并重建指定平台的浏览器 Context 与适配器 Page。
     */
    private void recoverPlatformBrowser(AiPlatform platform, PlatformAdapter adapter) {
        try {
            adapter.close();
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
        playwrightManager.resetPlatform(platform);
        try {
            initializePlatformAdapter(platform);
        } catch (Exception e) {
            throw new RuntimeException("重置 " + platform.name() + " 浏览器失败: " + e.getMessage(), e);
        }
    }

    /**
     * 同步等待平台队列中的 Prompt 发送结果。
     */
    private String invokeAdapter(PlatformAdapter adapter, AiPlatform platform, String prompt) {
        try {
            return submitSendPrompt(adapter, platform, prompt)
                    .get(debateConfig.getAiTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException(platform.name() + " 响应超时", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("{} sendPrompt 异常: {}", platform.name(), cause.getMessage());
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(platform.name() + " 任务被中断", e);
        }
    }

    /**
     * 执行单轮辩论——向所有活跃平台发送 Prompt。
     */
    DebateRound executeRound(DebateSession session, int roundNum, RoundType type) {
        int roundTimeoutSeconds = resolveRoundTimeoutSeconds(session);
        log.debug("▶️ 开始第 {} 轮 [{}] — 活跃通道: {}, 超时: {}s",
                roundNum, type, session.getActiveChannelCount(), roundTimeoutSeconds);
        DebateRound round = DebateRound.builder()
                .roundNumber(roundNum)
                .roundType(type)
                .startedAt(LocalDateTime.now())
                .build();

        Map<String, CompletableFuture<String>> futures = new LinkedHashMap<>();
        for (String channelId : session.getActiveChannelIds()) {
            String prompt = buildChannelPrompt(type, channelId, session);
            round.addChannelPrompt(channelId, prompt);
            log.debug("  {} Prompt 长度: {} 字符", channelId, prompt.length());
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return invokeChannel(channelId, prompt);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, debateExecutor);
            futures.put(channelId, future);
            if (!debateConfig.isParallelExecution()) {
                try {
                    future.get(roundTimeoutSeconds, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.error("{} 串行发送失败: {}", channelId, extractFailureMessage(e));
                }
            }
        }

        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.values().toArray(new CompletableFuture[0]));
        try {
            allFutures.get(roundTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("⏰ 第 {} 轮超时（{}s），等待在途任务收尾…", roundNum, roundTimeoutSeconds);
            awaitChannelFuturesGracefully(futures, debateConfig.getAiTimeoutSeconds());
        } catch (Exception e) {
            log.error("第 {} 轮并行执行异常: {}", roundNum, e.getMessage());
        }

        for (var entry : futures.entrySet()) {
            String channelId = entry.getKey();
            try {
                if (entry.getValue().isDone() && !entry.getValue().isCancelled()) {
                    String response = entry.getValue().get();
                    String prompt = round.getChannelPrompt(channelId);
                    String topic = ResponseContentValidator.extractTopicFromPrompt(prompt);
                    if (!ResponseContentValidator.isValid(response, prompt, topic, type)) {
                        throw new BrowserAutomationException(
                                "回复无效：疑似误抓用户消息、内容过短或结构不完整（" + response.length() + " 字符）");
                    }
                    ParticipantResponse pr = channelRegistryService.toAiPlatform(channelId).isPresent()
                            ? ParticipantResponse.of(channelRegistryService.toAiPlatform(channelId).get(), response, 0)
                            : ParticipantResponse.ofChannel(channelId, response, 0);
                    round.addChannelResponse(channelId, pr);
                    channelRegistryService.toAiPlatform(channelId).ifPresent(p -> round.addResponse(p, pr));
                }
            } catch (Exception e) {
                String reason = extractFailureMessage(e);
                log.error("❌ {} 在第 {} 轮失败: {}", channelId, roundNum, reason);
                session.recordChannelFailure(channelId, roundNum, reason);
            }
        }

        if (session.getActiveChannelCount() < 2) {
            session.setFailedAtRound(roundNum);
            String detail = session.buildTerminalFailureDetail();
            session.markTerminalFailure(detail);
            throw new DebateFailedException(detail);
        }

        round.setCompletedAt(LocalDateTime.now());
        log.info("✅ 第 {} 轮完成 — 活跃通道: {}", roundNum, session.getActiveChannelCount());
        return round;
    }

    /**
     * 从异常链中提取可读失败原因。
     */
    private String extractFailureMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg != null && !msg.isBlank() ? msg : t.getClass().getSimpleName();
    }

    /**
     * 超时后等待在途 Future 收尾，避免下一轮与未完成的浏览器操作并发冲突。
     */
    private void awaitFuturesGracefully(Map<AiPlatform, CompletableFuture<String>> futures, int graceSeconds) {
        for (var entry : futures.entrySet()) {
            CompletableFuture<String> future = entry.getValue();
            if (future.isDone()) continue;
            try {
                future.get(graceSeconds, TimeUnit.SECONDS);
                log.debug("{} 在途任务已收尾", entry.getKey().name());
            } catch (Exception e) {
                log.warn("{} 在途任务未能收尾: {}", entry.getKey().name(), e.getMessage());
            }
        }
    }

    private void awaitChannelFuturesGracefully(Map<String, CompletableFuture<String>> futures, int graceSeconds) {
        for (var entry : futures.entrySet()) {
            CompletableFuture<String> future = entry.getValue();
            if (future.isDone()) continue;
            try {
                future.get(graceSeconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("{} 在途任务未能收尾: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /**
     * 第 2 轮：交叉批判。
     */
    DebateRound executeCritiqueRound(DebateSession session, int roundNum) {
        DebateRound round = DebateRound.builder()
                .roundNumber(roundNum)
                .roundType(RoundType.CRITIQUE)
                .startedAt(LocalDateTime.now())
                .build();

        for (String channelId : session.getActiveChannelIds()) {
            String critPrompt = promptBuilder.buildCritiquePromptForChannel(session, channelId);
            round.addChannelPrompt(channelId, critPrompt);
            try {
                String response = invokeChannel(channelId, critPrompt);
                ParticipantResponse pr = channelRegistryService.toAiPlatform(channelId).isPresent()
                        ? ParticipantResponse.of(channelRegistryService.toAiPlatform(channelId).get(), response, 0)
                        : ParticipantResponse.ofChannel(channelId, response, 0);
                round.addChannelResponse(channelId, pr);
                channelRegistryService.toAiPlatform(channelId).ifPresent(p -> round.addResponse(p, pr));
            } catch (Exception e) {
                log.error("{} 批判轮失败: {}", channelId, e.getMessage());
                session.markChannelFailed(channelId);
            }
        }

        round.setCompletedAt(LocalDateTime.now());
        log.info("✅ 第 {} 轮（批判）完成", roundNum);
        return round;
    }

    /**
     * 第 3+ 轮：反驳回应。
     */
    DebateRound executeRebuttalRound(DebateSession session, int roundNum) {
        DebateRound round = DebateRound.builder()
                .roundNumber(roundNum)
                .roundType(RoundType.REBUTTAL)
                .startedAt(LocalDateTime.now())
                .build();

        for (String channelId : session.getActiveChannelIds()) {
            String rebuttalPrompt = promptBuilder.buildRebuttalPromptForChannel(session, channelId);
            round.addChannelPrompt(channelId, rebuttalPrompt);
            try {
                String response = invokeChannel(channelId, rebuttalPrompt);
                ParticipantResponse pr = channelRegistryService.toAiPlatform(channelId).isPresent()
                        ? ParticipantResponse.of(channelRegistryService.toAiPlatform(channelId).get(), response, 0)
                        : ParticipantResponse.ofChannel(channelId, response, 0);
                round.addChannelResponse(channelId, pr);
                channelRegistryService.toAiPlatform(channelId).ifPresent(p -> round.addResponse(p, pr));
            } catch (Exception e) {
                log.error("{} 反驳轮失败: {}", channelId, e.getMessage());
                session.markChannelFailed(channelId);
            }
        }

        round.setCompletedAt(LocalDateTime.now());
        log.info("✅ 第 {} 轮（反驳）完成", roundNum);
        return round;
    }

    /** 构建指定通道与轮次类型的 Prompt。 */
    private String buildChannelPrompt(RoundType type, String channelId, DebateSession session) {
        return switch (type) {
            case INITIAL -> promptBuilder.buildInitialPromptForChannel(session, channelId);
            case CRITIQUE -> promptBuilder.buildCritiquePromptForChannel(session, channelId);
            case REBUTTAL -> promptBuilder.buildRebuttalPromptForChannel(session, channelId);
            case CONVERGENCE -> {
                Optional<AiPlatform> platform = channelRegistryService.toAiPlatform(channelId);
                if (platform.isPresent()) {
                    yield promptBuilder.buildConvergencePrompt(session, platform.get());
                }
                yield promptBuilder.buildInitialPromptForChannel(session, channelId);
            }
        };
    }

    /**
     * 收敛检查并更新回合的收敛结果。
     */
    private boolean checkAndSetConvergence(DebateSession session, DebateRound round) {
        List<ParticipantResponse> activeResponses = new ArrayList<>();
        for (String channelId : session.getActiveChannelIds()) {
            ParticipantResponse resp = round.getChannelResponse(channelId);
            if (resp != null) activeResponses.add(resp);
        }
        if (activeResponses.size() < 2) {
            for (AiPlatform p : AiPlatform.values()) {
                if (session.isPlatformActive(p)) {
                    ParticipantResponse resp = round.getResponse(p);
                    if (resp != null) activeResponses.add(resp);
                }
            }
        }

        if (activeResponses.size() < 2) return false;

        ConvergenceResult result = convergenceDetector.check(activeResponses);
        round.setConvergenceResult(result);

        // 初始轮不因短文本回声误判收敛
        if (round.getRoundType() == RoundType.INITIAL) {
            boolean anyTooShort = activeResponses.stream()
                    .anyMatch(r -> r.getContent() == null
                            || r.getContent().length() < 400);
            if (anyTooShort) {
                log.warn("初始轮存在过短回复，跳过收敛判定");
                return false;
            }
        }

        boolean converged = result.getMinPairwiseSimilarity() >= session.getConvergenceThreshold();
        if (converged) {
            result.setConverged(true);
            log.info("🎯 收敛达成! minPairwise={} >= threshold={}",
                    String.format("%.4f", result.getMinPairwiseSimilarity()),
                    session.getConvergenceThreshold());
        }
        return converged;
    }

    /**
     * 异步调用 DeepSeek API 裁判整理本轮材料；已收敛时跳过（改由最终裁判）。
     */
    private void runJudgeForRound(DebateSession session, DebateRound round) {
        JudgeService judge = resolveJudge(session);
        if (!judge.isJudgeEnabled(session)) {
            return;
        }
        if (round.getConvergenceResult() != null && round.getConvergenceResult().isConverged()) {
            log.debug("已收敛，跳过第 {} 轮整理（改由最终整理）", round.getRoundNumber());
            return;
        }
        String sessionId = session.getSessionId();
        int roundNum = round.getRoundNumber();
        CompletableFuture.runAsync(() -> {
            log.debug("⚖️ 开始第 {} 轮整理（异步）…", roundNum);
            round.setJudgeRecord(judge.summarizeRound(session, round));
            stateStore.saveSnapshot(sessionId, roundNum, session);
        }, debateExecutor);
    }

    /**
     * 完成辩论 —— 立即更新状态，报告与最终裁判在后台异步生成。
     */
    private void finishDebate(DebateSession session, DebateStatus finalStatus) {
        session.setStatus(finalStatus);
        log.info("🏁 辩论结束 — session={}, status={}, totalRounds={}",
                session.getSessionId(), finalStatus, session.getCurrentRoundNumber());

        stateStore.saveSnapshot(session.getSessionId(), session.getCurrentRoundNumber(), session);

        String sessionId = session.getSessionId();
        CompletableFuture.runAsync(() -> runPostDebateWork(session), debateExecutor)
                .whenComplete((ignored, ex) -> {
                    resolveJudge(session).cleanup(sessionId);
                    sessionCache.remove(sessionId);
                    cancelFlags.remove(sessionId);
                    if (ex != null) {
                        log.error("赛后处理失败 — session={}: {}", sessionId, ex.getMessage(), ex);
                    }
                });
    }

    /**
     * 赛后异步任务：最终整理 + 按勾选类型生成完整产出文档。
     */
    private void runPostDebateWork(DebateSession session) {
        try {
            JudgeService judge = resolveJudge(session);
            if (judge.isJudgeEnabled(session)) {
                log.info("⚖️ 开始最终整理 — session={}", session.getSessionId());
                session.setFinalJudgeRecord(judge.summarizeFinal(session));
                stateStore.saveSnapshot(session.getSessionId(), session.getCurrentRoundNumber(), session);
            }
            outputDocumentService.generateRequestedDocuments(session, judge);
            stateStore.saveSnapshot(session.getSessionId(), session.getCurrentRoundNumber(), session);
            log.info("📚 产出文档处理完成 — session={}", session.getSessionId());
        } catch (Exception e) {
            log.error("赛后文档生成失败: {}", e.getMessage(), e);
        }
    }

    // ---- 辅助方法 ----

    private DebateSession initSession(DebateRequest request, String sessionId) {
        List<String> outputTypes = outputDocumentService.resolveRequestedTypes(request.getOutputDocuments());
        JudgeMode mode = request.getJudgeMode() != null ? request.getJudgeMode() : JudgeMode.API;

        if (mode == JudgeMode.CHANNEL) {
            if (request.getJudgeChannel() == null) {
                throw new IllegalArgumentException("通道整理模式必须选择整理通道");
            }
        } else {
            if (!apiSettingsService.hasConfiguredApiKey(request.getJudgeApiKey())) {
                throw new IllegalArgumentException("API 整理模式必须填写整理服务 API Key，或在「通道配置」中保存全局 API Key");
            }
        }

        String model = apiSettingsService.resolveModel(request.getJudgeModel());
        List<String> channelIds = ParticipantSelectionHelper.resolveChannelIds(request);
        List<AiPlatform> selectedPlatforms = new ArrayList<>();
        for (String channelId : channelIds) {
            channelRegistryService.toAiPlatform(channelId).ifPresent(selectedPlatforms::add);
        }
        if (selectedPlatforms.isEmpty()) {
            selectedPlatforms = ParticipantSelectionHelper.resolveSelectedPlatforms(request);
        }

        return DebateSession.builder()
                .sessionId(sessionId)
                .topic(request.getTopic())
                .status(DebateStatus.CREATED)
                .maxRounds(request.getMaxRounds())
                .convergenceThreshold(request.getConvergenceThreshold())
                .judgeEnabled(true)
                .judgeMode(mode)
                .judgeModel(model)
                .judgeChannel(request.getJudgeChannel())
                .outputDocumentTypes(new ArrayList<>(outputTypes))
                .generatedDocuments(new LinkedHashMap<>())
                .selectedPlatforms(new ArrayList<>(selectedPlatforms))
                .selectedChannelIds(new ArrayList<>(channelIds))
                .customChannelAliases(ParticipantSelectionHelper.resolveChannelAliases(request))
                .customParticipantAliases(ParticipantSelectionHelper.resolveCustomAliases(request))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private long elapsedMs(DebateSession session) {
        return java.time.Duration.between(session.getCreatedAt(), LocalDateTime.now()).toMillis();
    }

    /**
     * 根据会话的 judgeMode 选择合适的整理服务实现。
     */
    private JudgeService resolveJudge(DebateSession session) {
        return session.getJudgeMode() == JudgeMode.CHANNEL
                ? judgeServices.get("channelJudgeService")
                : judgeServices.get("apiJudgeService");
    }

    private boolean isCancelled(String sessionId) {
        AtomicBoolean flag = cancelFlags.get(sessionId);
        return flag != null && flag.get();
    }

    /**
     * 取消指定辩论会话。
     */
    public void cancelDebate(String sessionId) {
        AtomicBoolean flag = cancelFlags.get(sessionId);
        if (flag != null) {
            flag.set(true);
            log.info("⏹️ 辩论取消请求已发送 — session={}", sessionId);
        }
    }

    /**
     * 同步校验辩论前置条件：用户勾选的参与方中至少 2 个已登录，否则抛出异常。
     */
    public void assertPlatformsReadyForDebate(DebateRequest request) {
        List<String> excluded = new ArrayList<>();
        List<String> selected = ParticipantSelectionHelper.resolveChannelIds(request);
        int eligible = 0;

        for (String channelId : selected) {
            try {
                ChannelDefinition def = channelRegistryService.getChannel(channelId);
                if (!def.isEnabled()) {
                    excluded.add(def.getDisplayName() + "(未启用)");
                    continue;
                }
                if (channelRegistryService.verifyChannelReadyForDebate(def)) {
                    eligible++;
                } else {
                    excluded.add(def.getDisplayName() + (def.getType() == ChannelType.API ? "(API 未配置)" : "(未登录)"));
                }
            } catch (IllegalArgumentException e) {
                excluded.add(channelId + "(不存在)");
            }
        }

        log.info("辩论通道预检 — 勾选: {}, 可参与: {}, 排除: {}", selected.size(), eligible, excluded);

        if (eligible < 2) {
            throw new InsufficientPlatformsException(eligible, excluded);
        }
    }

    /**
     * 同步校验辩论前置条件（未指定参与方时，兼容旧调用）。
     */
    public void assertPlatformsReadyForDebate() {
        assertPlatformsReadyForDebate(DebateRequest.builder().build());
    }

    /**
     * 获取当前活跃的会话（用于状态查询）。
     */
    public DebateSession getSession(String sessionId) {
        return sessionCache.get(sessionId);
    }

    /**
     * 根据通道注册表排除未就绪通道（预检后调用）。
     */
    private void excludeUnreadyChannels(DebateSession session) {
        log.info("🔍 排除未就绪通道…");
        for (String channelId : session.getSelectedChannelIdsOrDefault()) {
            try {
                ChannelDefinition def = channelRegistryService.getChannel(channelId);
                if (!channelRegistryService.verifyChannelReadyForDebate(def)) {
                    session.markChannelFailed(channelId);
                    log.warn("⛔ {} 已排除 — 未就绪", def.getDisplayName());
                }
            } catch (IllegalArgumentException e) {
                session.markChannelFailed(channelId);
                log.warn("⛔ {} 已排除 — 通道不存在", channelId);
            }
        }
        log.info("辩论参与通道: {}/{}", session.getActiveChannelCount(), session.getSelectedChannelIdsOrDefault().size());

        if (session.getActiveChannelCount() < 2) {
            throw new DebateFailedException("可参与通道不足（"
                    + session.getActiveChannelCount() + "），至少需要 2 个已就绪通道");
        }
    }

    /**
     * 在平台专用线程中初始化适配器 Page。
     */
    private void initializePlatformAdapter(AiPlatform platform) throws Exception {
        PlatformAdapter adapter = adapters.get(platform);
        if (adapter == null) {
            throw new IllegalStateException(platform.name() + " 适配器未注册");
        }
        SelectorProvider selectors = loadSelectors(platform);
        var page = profileManager.getOrCreatePage(platform, selectors);
        adapter.initialize(page);
    }

    /**
     * 初始化所有活跃平台的浏览器 Context 和适配器 Page。
     * 在辩论开始前调用一次。
     */
    private void initializeAdapters(DebateSession session) {
        log.info("🔧 初始化浏览器适配器…");
        for (String channelId : session.getActiveChannelIds()) {
            ChannelDefinition def;
            try {
                def = channelRegistryService.getChannel(channelId);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (def.getType() != ChannelType.BROWSER) {
                continue;
            }
            Optional<AiPlatform> platformOpt = channelRegistryService.toAiPlatform(def);
            if (platformOpt.isEmpty()) {
                continue;
            }
            AiPlatform platform = platformOpt.get();
            try {
                platformQueueManager.submit(platform, () -> {
                    initializePlatformAdapter(platform);
                    return null;
                }).get(90, TimeUnit.SECONDS);
                log.info("✅ {} 浏览器适配器已初始化", def.getDisplayName());
            } catch (Exception e) {
                String reason = extractFailureMessage(e);
                log.error("❌ {} 适配器初始化失败: {}", def.getDisplayName(), reason);
                session.recordChannelFailure(channelId, 0, reason);
            }
        }

        if (session.getActiveChannelCount() < 2) {
            session.setFailedAtRound(0);
            String detail = session.buildTerminalFailureDetail();
            session.markTerminalFailure(detail);
            throw new DebateFailedException(detail);
        }
    }

    /**
     * 计算单轮等待超时：API 通道经信号量串行调用，按活跃 API 通道数放大上限。
     */
    private int resolveRoundTimeoutSeconds(DebateSession session) {
        int base = debateConfig.getAiTimeoutSeconds();
        List<String> activeChannelIds = session.getActiveChannelIds();
        if (activeChannelIds.isEmpty()) {
            return base;
        }
        long apiChannelCount = activeChannelIds.stream()
                .filter(this::isApiChannel)
                .count();
        if (apiChannelCount <= 1) {
            return base;
        }
        int scaled = (int) (base * apiChannelCount);
        log.debug("API 通道串行等待 — {} 个通道，轮次超时 {}s", apiChannelCount, scaled);
        return scaled;
    }

    /**
     * 判断通道是否为 API 类型。
     */
    private boolean isApiChannel(String channelId) {
        try {
            return channelRegistryService.getChannel(channelId).getType() == ChannelType.API;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 向指定通道发送 Prompt（API 或浏览器）。
     */
    private String invokeChannel(String channelId, String prompt) throws Exception {
        ChannelDefinition def = channelRegistryService.getChannel(channelId);
        if (def.getType() == ChannelType.API) {
            return apiParticipantService.chat(def, prompt);
        }
        AiPlatform platform = channelRegistryService.toAiPlatform(def)
                .orElseThrow(() -> new IllegalStateException("浏览器通道未关联平台: " + channelId));
        PlatformAdapter adapter = adapters.get(platform);
        if (adapter == null) {
            throw new IllegalStateException(platform.name() + " 适配器未注册");
        }
        return invokeAdapter(adapter, platform, prompt);
    }

    /**
     * 清理讨论方适配器资源（辩论主流程结束后调用）。
     * 整理通道浏览器由 ChannelJudgeService 独立管理，不在此处关闭。
     */
    private void cleanupAdapters() {
        log.info("🧹 清理适配器资源…");
        for (var entry : adapters.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.warn("关闭 {} 适配器时出错: {}", entry.getKey(), e.getMessage());
            }
            try {
                playwrightManager.resetPlatform(entry.getKey());
            } catch (Exception e) {
                log.warn("重置 {} 浏览器资源时出错: {}", entry.getKey(), e.getMessage());
            }
        }
        log.info("✅ 适配器资源已清理");
    }

    private String getPlatformUrl(AiPlatform platform) {
        var platforms = platformProperties.getPlatforms();
        var config = platforms.get(platform.name().toLowerCase());
        return config != null ? config.getUrl() : "";
    }

    /**
     * 加载平台选择器配置。
     */
    private SelectorProvider loadSelectors(AiPlatform platform) {
        return YamlSelectorProvider.load(platform.name().toLowerCase(), getPlatformUrl(platform));
    }

    /**
     * 判断平台是否在配置中启用。
     */
    private boolean isPlatformEnabled(AiPlatform platform) {
        var config = platformProperties.getPlatforms().get(platform.name().toLowerCase());
        return config == null || config.isEnabled();
    }

    /**
     * 辩论失败异常 —— 无法恢复的场景。
     */
    private static class DebateFailedException extends RuntimeException {
        DebateFailedException(String message) {
            super(message);
        }
    }
}
