package com.debatearena.orchestrator;

import com.debatearena.adapter.PlatformAdapter;
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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
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
        if (request.getJudgeMode() == JudgeMode.API
                && request.getJudgeApiKey() != null && !request.getJudgeApiKey().isBlank()) {
            apiJudgeService.registerApiKey(sessionId, request.getJudgeApiKey());
        }
        activeJudge.registerSession(sessionId, session);
        sessionCache.put(sessionId, session);
        stateStore.saveSnapshot(sessionId, 0, session);

        log.info("🚀 辩论开始 — session={}, topic={}, maxRounds={}",
                sessionId, session.getTopic(), session.getMaxRounds());

        try {
            // --- 排除未登录 / 未就绪平台 ---
            excludeUnloggedPlatforms(session);
            promptBuilder.assignParticipantAliases(session);
            log.info("讨论方代号: {}", session.getParticipantAliases());

            // --- 初始化浏览器和适配器 ---
            initializeAdapters(session);

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
                session.setFailureReason(e.getMessage());
            }
            stateStore.saveSnapshot(sessionId, session.getCurrentRoundNumber(), session);
        } catch (Exception e) {
            log.error("💥 辩论异常 — session={}: {}", sessionId, e.getMessage(), e);
            session.setStatus(DebateStatus.FAILED);
            session.setFailureReason(e.getMessage() != null ? e.getMessage() : "未知异常");
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
        log.debug("▶️ 开始第 {} 轮 [{}] — 活跃平台: {}", roundNum, type, session.getActivePlatformCount());
        DebateRound round = DebateRound.builder()
                .roundNumber(roundNum)
                .roundType(type)
                .startedAt(LocalDateTime.now())
                .build();

        // 向各平台发送 Prompt（并行时各平台仍走独立单线程队列，避免 Playwright 跨线程）
        Map<AiPlatform, CompletableFuture<String>> futures = new EnumMap<>(AiPlatform.class);
        for (AiPlatform platform : session.getActivePlatforms()) {
            if (!session.isPlatformActive(platform)) continue;
            String prompt = buildPrompt(type, platform, session);
            round.addPrompt(platform, prompt);
            log.debug("  {} Prompt 长度: {} 字符", platform.name(), prompt.length());
            PlatformAdapter adapter = adapters.get(platform);
            CompletableFuture<String> future = submitSendPrompt(adapter, platform, prompt);
            futures.put(platform, future);
            if (!debateConfig.isParallelExecution()) {
                log.debug("串行模式 — 等待 {} 完成后再发送下一平台", platform.name());
                try {
                    future.get(debateConfig.getAiTimeoutSeconds(), TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.error("{} 串行发送失败: {}", platform.name(), extractFailureMessage(e));
                }
            }
        }

        // 等待所有完成（带超时）
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.values().toArray(new CompletableFuture[0]));

        try {
            allFutures.get(debateConfig.getAiTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("⏰ 第 {} 轮超时，等待在途任务收尾…", roundNum);
            awaitFuturesGracefully(futures, 60);
        } catch (Exception e) {
            log.error("第 {} 轮并行执行异常: {}", roundNum, e.getMessage());
        }

        // 收集结果
        for (var entry : futures.entrySet()) {
            AiPlatform platform = entry.getKey();
            try {
                if (entry.getValue().isDone() && !entry.getValue().isCancelled()) {
                    String response = entry.getValue().get();
                    round.addResponse(platform,
                            ParticipantResponse.of(platform, response, 0));
                }
            } catch (Exception e) {
                String reason = extractFailureMessage(e);
                log.error("❌ {} 在第 {} 轮失败，标记为 FAILED: {}", platform.name(), roundNum, reason);
                session.recordPlatformFailure(platform, roundNum, reason);
            }
        }

        // 优雅降级检查
        if (session.getActivePlatformCount() < 2) {
            session.setFailedAtRound(roundNum);
            String detail = session.getFailureReason() != null ? session.getFailureReason()
                    : "活跃平台不足（当前 " + session.getActivePlatformCount() + "，至少需要 2 个）";
            throw new DebateFailedException(detail);
        }

        round.setCompletedAt(LocalDateTime.now());
        log.info("✅ 第 {} 轮完成 — 活跃平台: {}", roundNum, session.getActivePlatformCount());
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

    /**
     * 第 2 轮：交叉批判（每个 AI 批判另外两个 AI 的回答）。
     */
    DebateRound executeCritiqueRound(DebateSession session, int roundNum) {
        DebateRound round = DebateRound.builder()
                .roundNumber(roundNum)
                .roundType(RoundType.CRITIQUE)
                .startedAt(LocalDateTime.now())
                .build();

        for (AiPlatform critic : session.getActivePlatforms()) {
            if (!session.isPlatformActive(critic)) continue;

            String critPrompt = promptBuilder.buildCritiquePrompt(session, critic);

            round.addPrompt(critic, critPrompt);
            try {
                String response = invokeAdapter(adapters.get(critic), critic, critPrompt);
                round.addResponse(critic, ParticipantResponse.of(critic, response, 0));
            } catch (Exception e) {
                log.error("{} 批判轮失败: {}", critic.name(), e.getMessage());
                session.markPlatformFailed(critic);
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

        for (AiPlatform platform : session.getActivePlatforms()) {
            if (!session.isPlatformActive(platform)) continue;

            String rebuttalPrompt = promptBuilder.buildRebuttalPrompt(
                    session, platform, promptBuilder.buildCritiquesForRebuttal(session, platform));

            round.addPrompt(platform, rebuttalPrompt);
            try {
                String response = invokeAdapter(adapters.get(platform), platform, rebuttalPrompt);
                round.addResponse(platform, ParticipantResponse.of(platform, response, 0));
            } catch (Exception e) {
                log.error("{} 反驳轮失败: {}", platform.name(), e.getMessage());
                session.markPlatformFailed(platform);
            }
        }

        round.setCompletedAt(LocalDateTime.now());
        log.info("✅ 第 {} 轮（反驳）完成", roundNum);
        return round;
    }

    /**
     * 构建指定轮次类型和平台的 Prompt。
     */
    private String buildPrompt(RoundType type, AiPlatform platform, DebateSession session) {
        return switch (type) {
            case INITIAL -> promptBuilder.buildInitialPrompt(session, platform);
            case CRITIQUE -> promptBuilder.buildCritiquePrompt(session, platform);
            case REBUTTAL -> promptBuilder.buildRebuttalPrompt(
                    session, platform, promptBuilder.buildCritiquesForRebuttal(session, platform));
            case CONVERGENCE -> promptBuilder.buildConvergencePrompt(session, platform);
        };
    }

    /**
     * 收敛检查并更新回合的收敛结果。
     */
    private boolean checkAndSetConvergence(DebateSession session, DebateRound round) {
        List<ParticipantResponse> activeResponses = new ArrayList<>();
        for (AiPlatform p : AiPlatform.values()) {
            if (session.isPlatformActive(p)) {
                ParticipantResponse resp = round.getResponse(p);
                if (resp != null) activeResponses.add(resp);
            }
        }

        if (activeResponses.size() < 2) return false;

        ConvergenceResult result = convergenceDetector.check(activeResponses);
        round.setConvergenceResult(result);

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
     * 赛后异步任务：按勾选类型生成完整产出文档。
     */
    private void runPostDebateWork(DebateSession session) {
        try {
            outputDocumentService.generateRequestedDocuments(session, resolveJudge(session));
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
            boolean hasApiKey = request.getJudgeApiKey() != null && !request.getJudgeApiKey().isBlank();
            if (!hasApiKey) {
                throw new IllegalArgumentException("API 整理模式必须填写整理服务 API Key");
            }
        }

        String model = request.getJudgeModel() != null && !request.getJudgeModel().isBlank()
                ? request.getJudgeModel() : "deepseek-v4-flash";
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
     * 同步校验辩论前置条件：至少 2 个平台已登录，否则抛出异常。
     * 在 REST 层启动辩论前调用，避免未登录 Profile 进入辩论流程。
     */
    public void assertPlatformsReadyForDebate() {
        List<String> excluded = new ArrayList<>();
        int eligible = 0;

        for (AiPlatform platform : AiPlatform.values()) {
            if (!isPlatformEnabled(platform)) {
                excluded.add(platform.name() + "(未启用)");
                continue;
            }
            SelectorProvider selectors = loadSelectors(platform);
            if (profileManager.isEligibleForDebate(platform, selectors)) {
                eligible++;
            } else {
                excluded.add(platform.name() + "(未登录)");
            }
        }

        log.info("辩论平台预检 — 可参与: {}, 排除: {}", eligible, excluded);

        if (eligible < 2) {
            throw new InsufficientPlatformsException(eligible, excluded);
        }
    }

    /**
     * 获取当前活跃的会话（用于状态查询）。
     */
    public DebateSession getSession(String sessionId) {
        return sessionCache.get(sessionId);
    }

    /**
     * 根据缓存的登录状态排除未登录平台（预检后调用，避免重复 DOM 检测）。
     */
    private void excludeUnloggedPlatforms(DebateSession session) {
        log.info("🔍 排除未登录平台…");
        for (AiPlatform platform : AiPlatform.values()) {
            if (!isPlatformEnabled(platform)) {
                session.markPlatformFailed(platform);
                log.info("⛔ {} 已排除 — 平台未启用", platform.name());
                continue;
            }
            if (!profileManager.isProfileReady(platform)
                    || profileManager.getEffectiveLoginStatus(platform) != LoginStatus.LOGGED_IN) {
                session.markPlatformFailed(platform);
                log.warn("⛔ {} 已排除 — 未登录或 Profile 未就绪", platform.name());
            }
        }
        log.info("辩论参与平台: {}/{}", session.getActivePlatformCount(), AiPlatform.values().length);

        if (session.getActivePlatformCount() < 2) {
            throw new DebateFailedException("可参与平台不足（"
                    + session.getActivePlatformCount() + "），至少需要 2 个已登录平台");
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
        for (AiPlatform platform : AiPlatform.values()) {
            if (!session.isPlatformActive(platform)) continue;
            try {
                platformQueueManager.submit(platform, () -> {
                    initializePlatformAdapter(platform);
                    return null;
                }).get(90, TimeUnit.SECONDS);
                log.info("✅ {} 适配器已初始化", platform.name());
            } catch (Exception e) {
                String reason = extractFailureMessage(e);
                log.error("❌ {} 适配器初始化失败: {}", platform.name(), reason);
                session.recordPlatformFailure(platform, 0, reason);
            }
        }

        if (session.getActivePlatformCount() < 2) {
            session.setFailedAtRound(0);
            String detail = session.getFailureReason() != null ? session.getFailureReason()
                    : "初始化后活跃平台不足（" + session.getActivePlatformCount() + "）";
            throw new DebateFailedException(detail);
        }
    }

    /**
     * 清理所有适配器资源（辩论结束后调用）。
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
