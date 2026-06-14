package com.debatearena.browser;

import com.debatearena.config.BrowserConfig;
import com.debatearena.model.AiPlatform;
import com.microsoft.playwright.*;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

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

    /** 每个平台独立的 Playwright 驱动。 */
    private final Map<AiPlatform, Playwright> playwrights = new EnumMap<>(AiPlatform.class);

    /** 每个 AI 平台对应一个持久化 BrowserContext。 */
    private final Map<AiPlatform, BrowserContext> contexts = new EnumMap<>(AiPlatform.class);

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

        BrowserType.LaunchPersistentContextOptions options = buildLaunchOptions(config.isHeadless());
        if ("chrome".equals(config.getChannel())) {
            options.setChannel("chrome");
        }

        BrowserContext context = getPlaywright(platform).chromium()
                .launchPersistentContext(userDataDir, options);

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

        BrowserType.LaunchPersistentContextOptions options = buildLaunchOptions(false);
        if ("chrome".equals(config.getChannel())) {
            options.setChannel("chrome");
        }

        BrowserContext context = getPlaywright(platform).chromium()
                .launchPersistentContext(userDataDir, options);

        log.info("✅ {} 交互式浏览器已打开 — profileDir={}", platform.name(), userDataDir);
        return context;
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

    public synchronized void closeAllContexts() {
        for (AiPlatform platform : AiPlatform.values()) {
            closeContext(platform);
        }
    }

    public synchronized int getActiveContextCount() {
        return contexts.size();
    }

    @PreDestroy
    public void destroy() {
        log.info("🔒 关闭所有浏览器资源...");
        closeAllContexts();
        for (AiPlatform platform : AiPlatform.values()) {
            closePlaywright(platform);
        }
        log.info("✅ 所有 Playwright 资源已关闭");
    }
}
