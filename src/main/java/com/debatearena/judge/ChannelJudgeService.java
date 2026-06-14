package com.debatearena.judge;

import com.debatearena.adapter.*;
import com.debatearena.browser.PlaywrightManager;
import com.debatearena.browser.ProfileManager;
import com.debatearena.browser.SelectorProvider;
import com.debatearena.browser.YamlSelectorProvider;
import com.debatearena.config.AiPlatformProperties;
import com.debatearena.config.DebateConfig;
import com.debatearena.model.*;
import com.debatearena.orchestrator.PlatformQueueManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 通道整理服务 —— 通过浏览器自动化向指定 AI 通道发送整理提示词。
 * <p>
 * 每个研讨会话维护独立的 Playwright 驱动、BrowserContext 与 PlatformAdapter，
 * 与讨论方浏览器完全隔离（独立 Profile 目录 + 独立任务队列）。
 */
@Slf4j
@Service("channelJudgeService")
@RequiredArgsConstructor
public class ChannelJudgeService implements JudgeService {

    private final PlaywrightManager playwrightManager;
    private final ProfileManager profileManager;
    private final PlatformQueueManager platformQueueManager;
    private final AiPlatformProperties platformProperties;
    private final DebateConfig debateConfig;

    /** sessionId → JudgePageContext（每会话独立的浏览器页面与适配器）。 */
    private final Map<String, JudgePageContext> sessionContexts = new ConcurrentHashMap<>();

    private volatile String roundCombinedPrompt;
    private volatile String finalCombinedPrompt;
    private volatile String documentCombinedPrompt;

    // ---- JudgeService implementation ----

    @Override
    public void registerSession(String sessionId, DebateSession session) {
        AiPlatform platform = session.getJudgeChannel();
        if (platform == null) {
            throw new IllegalArgumentException("通道整理模式必须指定整理通道");
        }

        String platformKey = platform.name().toLowerCase();
        String url = getPlatformUrl(platformKey);
        SelectorProvider selectors = YamlSelectorProvider.load(platformKey, url);

        // 整理通道使用独立 Profile，避免与讨论方共用 BrowserContext 时互相关闭
        profileManager.ensureJudgeProfileSeeded(platform);
        BrowserContext context = playwrightManager.launchJudgePersistentContext(
                sessionId, platform, profileManager.getJudgeProfilePath(platform));

        // 创建独立的 Page 与 Adapter
        Page page = context.newPage();
        log.info("📄 为整理通道 {} 创建独立浏览器页面 — session={}", platform.name(), sessionId);

        // 创建独立的 Adapter 实例
        PlatformAdapter adapter = createAdapter(platform, selectors);
        adapter.initialize(page);

        JudgePageContext ctx = new JudgePageContext(platform, page, adapter, selectors);
        sessionContexts.put(sessionId, ctx);
        log.info("✅ 通道整理方已就绪 — session={}, platform={}", sessionId, platform.name());
    }

    @Override
    public boolean isJudgeEnabled(DebateSession session) {
        return session.getJudgeMode() == JudgeMode.CHANNEL
                && session.getJudgeChannel() != null
                && sessionContexts.containsKey(session.getSessionId());
    }

    @Override
    public JudgeRoundRecord summarizeRound(DebateSession session, DebateRound round) {
        JudgePageContext ctx = sessionContexts.get(session.getSessionId());
        if (ctx == null) {
            return JudgeRoundRecord.failure("整理通道未就绪");
        }

        String userPrompt = buildRoundContext(session, round);
        String combinedPrompt = buildCombinedRoundPrompt(userPrompt);

        return sendJudgePrompt(ctx, combinedPrompt,
                "第 " + round.getRoundNumber() + " 轮整理",
                session.getSessionId());
    }

    @Override
    public JudgeRoundRecord summarizeFinal(DebateSession session) {
        JudgePageContext ctx = sessionContexts.get(session.getSessionId());
        if (ctx == null) {
            return JudgeRoundRecord.failure("整理通道未就绪");
        }

        String userPrompt = buildFinalContext(session);
        String combinedPrompt = buildCombinedFinalPrompt(userPrompt);

        return sendJudgePrompt(ctx, combinedPrompt,
                "最终整理",
                session.getSessionId());
    }

    @Override
    public String generateDocumentContent(DebateSession session, String systemPrompt, String userPrompt) {
        JudgePageContext ctx = sessionContexts.get(session.getSessionId());
        if (ctx == null) {
            throw new IllegalStateException("整理通道未就绪");
        }

        String combinedPrompt = buildCombinedDocumentPrompt(systemPrompt, userPrompt);
        JudgeRoundRecord result = sendJudgePrompt(ctx, combinedPrompt,
                "产出文档生成",
                session.getSessionId());

        if (!result.isSuccess()) {
            throw new RuntimeException("文档生成失败: " + result.getErrorMessage());
        }
        return result.getAnalysis();
    }

    @Override
    public void cleanup(String sessionId) {
        JudgePageContext ctx = sessionContexts.remove(sessionId);
        if (ctx == null) return;

        try {
            ctx.adapter.close();
        } catch (Exception e) {
            log.warn("关闭整理通道适配器异常 — session={}: {}", sessionId, e.getMessage());
        }
        try {
            if (ctx.page != null && !ctx.page.isClosed()) {
                ctx.page.close();
            }
        } catch (Exception e) {
            log.warn("关闭整理通道页面异常 — session={}: {}", sessionId, e.getMessage());
        }
        playwrightManager.closeJudgeResources(sessionId);
        log.info("🔒 整理通道资源已清理 — session={}, platform={}", sessionId, ctx.platform.name());
    }

    // ---- Internal ----

    /**
     * 向整理通道发送提示词并等待响应。
     */
    private JudgeRoundRecord sendJudgePrompt(JudgePageContext ctx, String prompt,
                                             String label, String sessionId) {
        try {
            // 每次整理前重置对话上下文，避免跨轮内容干扰
            try {
                ctx.adapter.resetConversation();
            } catch (Exception e) {
                log.warn("重置对话上下文失败（非致命）: {}", e.getMessage());
            }

            // 通过整理通道专用队列提交，避免与讨论方共用平台时互相阻塞或污染
            CompletableFuture<String> future = platformQueueManager.submitForJudge(
                    ctx.platform,
                    () -> ctx.adapter.sendPrompt(prompt)
                            .get(debateConfig.getAiTimeoutSeconds(), TimeUnit.SECONDS)
            );

            String response = future.get(debateConfig.getAiTimeoutSeconds() + 10, TimeUnit.SECONDS);
            String cleaned = DocumentContentSanitizer.sanitize(response);
            log.info("⚖️ {} 通道整理完成 ({} 字符) — session={}",
                    label, cleaned.length(), sessionId);
            return JudgeRoundRecord.success(cleaned);

        } catch (java.util.concurrent.TimeoutException e) {
            log.error("通道整理超时 — {}: {}", label, e.getMessage());
            return JudgeRoundRecord.failure(label + "超时");
        } catch (Exception e) {
            log.error("通道整理失败 — {}: {}", label, e.getMessage());
            return JudgeRoundRecord.failure(e.getMessage());
        }
    }

    // ---- Platform adapter factory ----

    private PlatformAdapter createAdapter(AiPlatform platform, SelectorProvider selectors) {
        FallbackSelector fallback = new FallbackSelector();
        return switch (platform) {
            case CHATGPT -> new ChatGPTAdapter(selectors, fallback);
            case DEEPSEEK -> new DeepSeekAdapter(selectors, fallback);
            case GEMINI -> new GeminiAdapter(selectors, fallback);
        };
    }

    private String getPlatformUrl(String key) {
        var platforms = platformProperties.getPlatforms();
        var config = platforms.get(key);
        return config != null ? config.getUrl() : "";
    }

    // ---- Prompt context building (mirrors ApiJudgeService) ----

    private String buildRoundContext(DebateSession session, DebateRound round) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 需求描述 ===\n").append(session.getTopic()).append("\n\n");
        sb.append("=== 轮次信息 ===\n");
        sb.append("第 ").append(round.getRoundNumber()).append(" 轮 / 类型: ")
                .append(round.getRoundType()).append("\n");
        sb.append("参与讨论方: ").append(session.getParticipatingPlatforms().size()).append(" 人\n\n");

        if (round.getConvergenceResult() != null) {
            ConvergenceResult c = round.getConvergenceResult();
            sb.append("=== 收敛检测 ===\n");
            sb.append("minPairwise=").append(String.format("%.4f", c.getMinPairwiseSimilarity()));
            sb.append(", avgPairwise=").append(String.format("%.4f", c.getAverageSimilarity()));
            sb.append(", converged=").append(c.isConverged()).append("\n\n");
        }

        sb.append("=== 各讨论方材料 ===\n");
        for (AiPlatform platform : session.getActivePlatforms()) {
            String prompt = round.getPrompts().get(platform);
            ParticipantResponse response = round.getResponse(platform);
            if (prompt == null && response == null) continue;

            sb.append("\n--- ").append(session.getParticipantAlias(platform)).append(" ---\n");
            if (prompt != null) {
                sb.append("[发送的提示词]\n").append(truncate(prompt, 6000)).append("\n\n");
            }
            if (response != null && response.getContent() != null) {
                sb.append("[收到的回答]\n").append(truncate(response.getContent(), 6000)).append("\n");
            }
        }
        return sb.toString();
    }

    private String buildFinalContext(DebateSession session) {
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

            for (AiPlatform platform : session.getActivePlatforms()) {
                ParticipantResponse resp = round.getResponse(platform);
                if (resp != null && resp.getContent() != null) {
                    sb.append("\n[").append(session.getParticipantAlias(platform)).append(" 回答摘要]\n")
                            .append(truncate(resp.getContent(), 2000)).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n... (已截断)";
    }

    // ---- Combined prompt template loading ----

    private String buildCombinedRoundPrompt(String userPrompt) {
        return getRoundCombinedPrompt().replace("{user_prompt}", userPrompt);
    }

    private String buildCombinedFinalPrompt(String userPrompt) {
        return getFinalCombinedPrompt().replace("{user_prompt}", userPrompt);
    }

    private String buildCombinedDocumentPrompt(String systemPrompt, String userPrompt) {
        return getDocumentCombinedPrompt()
                .replace("{system_prompt}", systemPrompt)
                .replace("{user_prompt}", userPrompt);
    }

    private String getRoundCombinedPrompt() {
        if (roundCombinedPrompt == null) {
            roundCombinedPrompt = loadResource("templates/judge/round-combined-prompt.txt");
        }
        return roundCombinedPrompt;
    }

    private String getFinalCombinedPrompt() {
        if (finalCombinedPrompt == null) {
            finalCombinedPrompt = loadResource("templates/judge/final-combined-prompt.txt");
        }
        return finalCombinedPrompt;
    }

    private String getDocumentCombinedPrompt() {
        if (documentCombinedPrompt == null) {
            documentCombinedPrompt = loadResource("templates/judge/document-combined-prompt.txt");
        }
        return documentCombinedPrompt;
    }

    private String loadResource(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("无法加载整理模板: " + path, e);
        }
    }

    // ---- Inner class ----

    /**
     * 每个研讨会话的整理通道上下文 —— 持有独立 Profile 下的 Page 与 Adapter。
     */
    private static class JudgePageContext {
        final AiPlatform platform;
        final Page page;
        final PlatformAdapter adapter;
        final SelectorProvider selectors;

        JudgePageContext(AiPlatform platform, Page page, PlatformAdapter adapter, SelectorProvider selectors) {
            this.platform = platform;
            this.page = page;
            this.adapter = adapter;
            this.selectors = selectors;
        }
    }
}
