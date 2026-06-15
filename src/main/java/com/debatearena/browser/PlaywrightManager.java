package com.debatearena.browser;

import com.debatearena.config.BrowserConfig;
import com.debatearena.model.AiPlatform;
import com.microsoft.playwright.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Playwright 生命周期管理器。
 * <p>
 * 每个 AI 平台使用独立的 Playwright 驱动实例与持久化 BrowserContext，
 * 避免多平台并行操作时共享 worker 连接导致 {@code Object doesn't exist: worker@...} 异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaywrightManager {

    private final BrowserConfig config;
    private final BrowserProcessCleaner browserProcessCleaner;

    /** 每个平台独立的 Playwright 驱动。 */
    private final Map<AiPlatform, Playwright> playwrights = new EnumMap<>(AiPlatform.class);

    /** 每个 AI 平台对应一个持久化 BrowserContext（讨论方专用）。 */
    private final Map<AiPlatform, BrowserContext> contexts = new EnumMap<>(AiPlatform.class);

    /** 每个研讨会话对应一套独立的整理通道浏览器资源（与讨论方隔离）。 */
    private final Map<String, JudgeBrowserResources> judgeResources = new ConcurrentHashMap<>();

    /** 防止重复关闭。 */
    private volatile boolean destroyed = false;

    /**
     * 注册 JVM 关闭钩子，确保强制退出时尽量释放浏览器资源。
     */
    @PostConstruct
    public void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                destroy();
            } catch (Exception e) {
                log.warn("Shutdown hook 关闭浏览器失败: {}", e.getMessage());
            }
        }, "playwright-shutdown-hook"));
    }

    /**
     * 获取指定平台的 Playwright 实例（懒创建）。
     */
    public synchronized Playwright getPlaywright(AiPlatform platform) {
        return playwrights.computeIfAbsent(platform, p -> {
            log.info("🎭 为 {} 创建独立 Playwright 实例", p.name());
            return Playwright.create();
        });
    }

    /**
     * 为指定平台启动持久化 BrowserContext。
     */
    public synchronized BrowserContext launchPersistentContext(AiPlatform platform, Path userDataDir) {
        if (contexts.containsKey(platform)) {
            log.warn("平台 {} 的 BrowserContext 已存在，先关闭旧的", platform.name());
            closeContext(platform);
        }
        BrowserContext context = launchPersistentContextInternal(platform, userDataDir, config.isHeadless());
        contexts.put(platform, context);
        log.info("✅ {} BrowserContext 已启动 — headless={}, profileDir={}",
                platform.name(), config.isHeadless(), userDataDir);
        return context;
    }

    /**
     * 为交互式手动登录启动可见浏览器（调用方负责关闭返回的 Context）。
     */
    public synchronized BrowserContext launchPersistentContextForSetup(AiPlatform platform, Path userDataDir) {
        log.info("🖥️ 为 {} 启动交互式登录浏览器...", platform.name());
        BrowserContext context = launchPersistentContextInternal(platform, userDataDir, false);
        log.info("✅ {} 交互式登录浏览器已打开 — profileDir={}", platform.name(), userDataDir);
        return context;
    }

    /**
     * 启动用于后台登录检测的浏览器（遵循 headless 配置，非用户可见）。
     */
    public synchronized BrowserContext launchPersistentContextForVerification(AiPlatform platform, Path userDataDir) {
        log.debug("🔍 为 {} 启动登录检测浏览器 — headless={}", platform.name(), config.isHeadless());
        return launchPersistentContextInternal(platform, userDataDir, config.isHeadless());
    }

    /**
     * 启动持久化 BrowserContext；若 Profile 被占用则清理残留进程后重试一次。
     */
    private BrowserContext launchPersistentContextInternal(AiPlatform platform, Path userDataDir, boolean headless) {
        try {
            return doLaunchPersistentContext(platform, userDataDir, headless);
        } catch (Exception first) {
            if (!browserProcessCleaner.isProfileInUseError(first)) {
                throw first;
            }
            log.warn("平台 {} Profile 被占用，清理残留浏览器后重试: {}", platform.name(), first.getMessage());
            browserProcessCleaner.cleanupOrphanedBrowsers("launch-retry-" + platform.name());
            return doLaunchPersistentContext(platform, userDataDir, headless);
        }
    }

    /**
     * 实际调用 Playwright 启动持久化上下文。
     */
    private BrowserContext doLaunchPersistentContext(AiPlatform platform, Path userDataDir, boolean headless) {
        BrowserType.LaunchPersistentContextOptions options = buildLaunchOptions(headless);
        if ("chrome".equals(config.getChannel())) {
            options.setChannel("chrome");
        }
        return getPlaywright(platform).chromium().launchPersistentContext(userDataDir, options);
    }

    /**
     * 构建浏览器启动参数。
     */
    private BrowserType.LaunchPersistentContextOptions buildLaunchOptions(boolean headless) {
        return new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(headless)
                .setViewportSize(config.getViewportWidth(), config.getViewportHeight())
                .setSlowMo(config.getSlowMo())
                .setArgs(Arrays.asList(
                        "--disable-blink-features=AutomationControlled",
                        "--disable-features=DevToolsDebuggingRestrictions"
                ));
    }

    /**
     * 关闭指定平台的 BrowserContext。
     */
    public synchronized void closeContext(AiPlatform platform) {
        BrowserContext ctx = contexts.remove(platform);
        if (ctx != null) {
            try {
                ctx.close();
                log.info("🔒 {} BrowserContext 已关闭", platform.name());
            } catch (Exception e) {
                log.error("关闭 {} BrowserContext 失败: {}", platform.name(), e.getMessage());
            }
        }
    }

    /**
     * 关闭指定平台的 Playwright 驱动（通常在 Context 关闭后调用）。
     */
    public synchronized void closePlaywright(AiPlatform platform) {
        Playwright pw = playwrights.remove(platform);
        if (pw != null) {
            try {
                pw.close();
                log.info("🔒 {} Playwright 已关闭", platform.name());
            } catch (Exception e) {
                log.error("关闭 {} Playwright 失败: {}", platform.name(), e.getMessage());
            }
        }
    }

    /**
     * 完全重置指定平台的浏览器资源（Context + Playwright）。
     */
    public synchronized void resetPlatform(AiPlatform platform) {
        closeContext(platform);
        closePlaywright(platform);
    }

    public synchronized BrowserContext getContext(AiPlatform platform) {
        return contexts.get(platform);
    }

    /**
     * 为整理通道启动独立的持久化 BrowserContext（按会话隔离，不复用讨论方 Context）。
     */
    public synchronized BrowserContext launchJudgePersistentContext(String sessionId,
                                                                    AiPlatform platform,
                                                                    Path userDataDir) {
        if (judgeResources.containsKey(sessionId)) {
            log.warn("整理通道 {} 的 BrowserContext 已存在，先关闭旧的 — session={}",
                    platform.name(), sessionId);
            closeJudgeResources(sessionId);
        }
        try {
            return doLaunchJudgePersistentContext(sessionId, platform, userDataDir);
        } catch (Exception e) {
            if (!browserProcessCleaner.isProfileInUseError(e)) {
                throw e;
            }
            log.warn("整理通道 Profile 被占用，清理后重试 — session={}: {}", sessionId, e.getMessage());
            browserProcessCleaner.cleanupOrphanedBrowsers("judge-retry-" + sessionId);
            return doLaunchJudgePersistentContext(sessionId, platform, userDataDir);
        }
    }

    /**
     * 实际启动整理通道独立 BrowserContext。
     */
    private BrowserContext doLaunchJudgePersistentContext(String sessionId,
                                                           AiPlatform platform,
                                                           Path userDataDir) {
        Playwright playwright = Playwright.create();
        BrowserType.LaunchPersistentContextOptions options = buildLaunchOptions(config.isHeadless());
        if ("chrome".equals(config.getChannel())) {
            options.setChannel("chrome");
        }
        BrowserContext context = playwright.chromium().launchPersistentContext(userDataDir, options);
        judgeResources.put(sessionId, new JudgeBrowserResources(platform, playwright, context));
        log.info("✅ 整理通道 {} 独立 BrowserContext 已启动 — session={}, profileDir={}",
                platform.name(), sessionId, userDataDir);
        return context;
    }

    /**
     * 关闭指定会话的整理通道浏览器资源（Context + Playwright）。
     */
    public synchronized void closeJudgeResources(String sessionId) {
        JudgeBrowserResources resources = judgeResources.remove(sessionId);
        if (resources == null) {
            return;
        }
        try {
            if (resources.context() != null) {
                resources.context().close();
            }
        } catch (Exception e) {
            log.warn("关闭整理通道 BrowserContext 失败 — session={}: {}", sessionId, e.getMessage());
        }
        try {
            if (resources.playwright() != null) {
                resources.playwright().close();
            }
        } catch (Exception e) {
            log.warn("关闭整理通道 Playwright 失败 — session={}: {}", sessionId, e.getMessage());
        }
        log.info("🔒 整理通道独立浏览器已关闭 — session={}, platform={}",
                sessionId, resources.platform().name());
    }

    /**
     * 整理通道浏览器资源持有者。
     */
    private record JudgeBrowserResources(AiPlatform platform, Playwright playwright, BrowserContext context) {
    }

    /**
     * 关闭所有讨论方 BrowserContext。
     */
    public synchronized void closeAllContexts() {
        for (AiPlatform platform : AiPlatform.values()) {
            closeContext(platform);
        }
    }

    public synchronized int getActiveContextCount() {
        return contexts.size();
    }

    /**
     * 关闭全部 Playwright 浏览器资源（Spring 销毁与 JVM 退出时调用）。
     */
    @PreDestroy
    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        log.info("🔒 关闭所有浏览器资源...");
        for (String sessionId : judgeResources.keySet().toArray(new String[0])) {
            closeJudgeResources(sessionId);
        }
        closeAllContexts();
        for (AiPlatform platform : AiPlatform.values()) {
            closePlaywright(platform);
        }
        log.info("✅ 所有 Playwright 资源已关闭");
    }
}
