package com.debatearena.browser;

import com.debatearena.config.BrowserConfig;
import com.debatearena.model.AiPlatform;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 浏览器 Profile 管理器 —— 管理 {@code profiles/{chatgpt,gemini,deepseek}} 持久化目录。
 * <p>
 * 职责：
 * <ul>
 *   <li>创建/删除各平台的持久化 Profile 目录</li>
 *   <li>检测 Profile 是否就绪（是否存在、是否已登录）</li>
 *   <li>协调首次手动登录流程</li>
 * </ul>
 * <p>
 * 关键约束：健康检查为纯 DOM 检测，不发测试 Prompt。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileManager {

    private final BrowserConfig browserConfig;
    private final PlaywrightManager playwrightManager;

    /** 各平台最近一次检测到的登录状态（健康检查 / 交互登录后更新）。 */
    private final ConcurrentMap<AiPlatform, LoginStatus> loginStatusCache = new ConcurrentHashMap<>();

    /**
     * 获取指定平台的 Profile 路径。
     */
    public Path getProfilePath(AiPlatform platform) {
        return resolveProfileBasePath().resolve(platform.name().toLowerCase());
    }

    /**
     * 获取整理通道专用的独立 Profile 路径（与讨论方 Profile 分离，避免浏览器锁冲突）。
     */
    public Path getJudgeProfilePath(AiPlatform platform) {
        return resolveProfileBasePath().resolve("judge").resolve(platform.name().toLowerCase());
    }

    /**
     * 展开 profile 根目录配置。
     */
    private Path resolveProfileBasePath() {
        String base = browserConfig.getProfileBaseDir();
        if (base.contains("${user.home}")) {
            base = base.replace("${user.home}", System.getProperty("user.home"));
        }
        return Paths.get(base);
    }

    /**
     * 确保整理通道 Profile 可用：若尚未初始化，则从讨论方 Profile 复制登录态种子。
     */
    public void ensureJudgeProfileSeeded(AiPlatform platform) {
        Path judgePath = getJudgeProfilePath(platform);
        if (isProfileReadyAt(judgePath)) {
            return;
        }
        Path mainPath = getProfilePath(platform);
        if (!isProfileReady(platform)) {
            throw new IllegalStateException(platform.name() + " 讨论方 Profile 未就绪，无法为整理通道复制登录态");
        }
        log.info("📋 整理通道 {} 首次使用，从讨论方 Profile 复制登录态 — {} → {}",
                platform.name(), mainPath, judgePath);
        copyProfileDirectory(mainPath, judgePath);
    }

    /**
     * 检查指定路径下的 Profile 是否包含浏览器会话数据。
     */
    private boolean isProfileReadyAt(Path profilePath) {
        if (!Files.exists(profilePath) || !Files.isDirectory(profilePath)) {
            return false;
        }
        Path defaultDir = profilePath.resolve("Default");
        return Files.exists(defaultDir) && Files.isDirectory(defaultDir);
    }

    /**
     * 递归复制 Profile 目录（用于整理通道首次种子同步）。
     */
    private void copyProfileDirectory(Path source, Path target) {
        try {
            if (Files.exists(target)) {
                deleteProfileDirectory(target);
            }
            Files.createDirectories(target);
            try (var files = Files.walk(source)) {
                files.forEach(sourcePath -> {
                    try {
                        Path relative = source.relativize(sourcePath);
                        Path destinationPath = target.resolve(relative);
                        if (Files.isDirectory(sourcePath)) {
                            Files.createDirectories(destinationPath);
                        } else {
                            Files.createDirectories(destinationPath.getParent());
                            Files.copy(sourcePath, destinationPath);
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException("复制 Profile 文件失败: " + sourcePath, e);
                    }
                });
            }
            log.info("✅ 整理通道 Profile 种子复制完成 — {}", target);
        } catch (Exception e) {
            throw new IllegalStateException("复制整理通道 Profile 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除指定 Profile 目录（整理通道种子复制前的清理）。
     */
    private void deleteProfileDirectory(Path profilePath) {
        try (var files = Files.walk(profilePath)) {
            files.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception e) {
            log.warn("清理 Profile 目录失败: {} — {}", profilePath, e.getMessage());
        }
    }

    /**
     * 检查 Profile 是否就绪（目录存在且包含浏览器会话数据）。
     */
    public boolean isProfileReady(AiPlatform platform) {
        Path profilePath = getProfilePath(platform);
        if (!Files.exists(profilePath) || !Files.isDirectory(profilePath)) {
            log.debug("{} Profile 目录不存在: {}", platform.name(), profilePath);
            return false;
        }
        // 检查是否有 Default/ 子目录（Chromium 持久化标志）
        Path defaultDir = profilePath.resolve("Default");
        boolean hasDefault = Files.exists(defaultDir) && Files.isDirectory(defaultDir);
        log.debug("{} Profile 就绪检查: path={}, hasDefault={}", platform.name(), profilePath, hasDefault);
        return hasDefault;
    }

    /**
     * 更新平台登录状态缓存（健康检查、交互登录、辩论初始化后调用）。
     */
    public void updateLoginStatus(AiPlatform platform, LoginStatus status) {
        loginStatusCache.put(platform, status);
        log.info("{} 登录状态已更新为 {}", platform.name(), status);
    }

    /**
     * 清除平台登录状态缓存（删除 Profile 时调用）。
     */
    public void clearLoginStatus(AiPlatform platform) {
        loginStatusCache.remove(platform);
        log.debug("{} 登录状态缓存已清除", platform.name());
    }

    /**
     * 获取平台有效登录状态：优先使用缓存，无缓存时根据 Profile 目录推断。
     */
    public LoginStatus getEffectiveLoginStatus(AiPlatform platform) {
        LoginStatus cached = loginStatusCache.get(platform);
        if (cached != null) {
            return cached;
        }
        return isProfileReady(platform) ? LoginStatus.LOGGED_IN : LoginStatus.LOGIN_REQUIRED;
    }

    /**
     * 根据登录状态和 Profile 就绪情况生成展示消息。
     */
    public String getStatusMessage(AiPlatform platform, LoginStatus loginStatus, boolean profileReady) {
        return switch (loginStatus) {
            case LOGGED_IN -> profileReady ? "Profile 已就绪" : "已登录";
            case LOGIN_REQUIRED -> profileReady ? "登录已过期，请重新登录" : "需要首次登录";
            case ERROR -> "检测异常，请点击「检查」重试";
        };
    }

    /**
     * 检查指定平台的登录状态（纯 DOM 检测，不发 Prompt）。
     * <p>
     * 使用轮询方式替代 {@code waitFor}，避免选择器不匹配时产生 Playwright TimeoutError 噪音。
     */
    public LoginStatus checkLoginStatus(BrowserContext context, AiPlatform platform,
                                         SelectorProvider selectorProvider) {
        Page page = null;
        try {
            page = context.newPage();
            String url = selectorProvider.getPlatformUrl();
            if (url == null || url.isBlank()) {
                log.error("{} 平台 URL 为空，无法检测", platform.name());
                return LoginStatus.ERROR;
            }

            page.navigate(url);
            page.waitForLoadState();
            // 等待 JS 渲染
            page.waitForTimeout(3000);

            // 综合检测（URL + 输入框 + CSS 指示器）
            LoginStatus status;
            if (isLoginIndicatorVisible(page, selectorProvider)) {
                log.info("✅ {} 已登录", platform.name());
                status = LoginStatus.LOGGED_IN;
            } else {
                log.info("{} 未登录", platform.name());
                status = LoginStatus.LOGIN_REQUIRED;
            }
            updateLoginStatus(platform, status);
            return status;

        } catch (Exception e) {
            // 只记录简要消息，不打印完整堆栈（可能是页面加载失败等临时问题）
            log.warn("{} 登录检测异常: {}", platform.name(), e.getMessage());
            updateLoginStatus(platform, LoginStatus.ERROR);
            return LoginStatus.ERROR;
        } finally {
            if (page != null) {
                try { page.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 判断平台是否具备参与辩论的资格（Profile 存在且 DOM 验证已登录）。
     * 未登录平台不会进入辩论流程。
     *
     * @return true 表示已登录可参与辩论
     */
    public boolean isEligibleForDebate(AiPlatform platform, SelectorProvider selectors) {
        if (!isProfileReady(platform)) {
            updateLoginStatus(platform, LoginStatus.LOGIN_REQUIRED);
            log.warn("⛔ {} 不具备辩论资格 — Profile 未初始化", platform.name());
            return false;
        }

        LoginStatus cached = loginStatusCache.get(platform);
        if (cached == LoginStatus.LOGIN_REQUIRED) {
            log.warn("⛔ {} 不具备辩论资格 — 未登录", platform.name());
            return false;
        }

        // 缓存已登录则跳过浏览器实体验证，避免每次启动多开 2 个浏览器（约 10s）
        if (cached == LoginStatus.LOGGED_IN) {
            log.debug("✅ {} 使用缓存登录状态，跳过浏览器预检", platform.name());
            return true;
        }

        return verifyLoginWithBrowser(platform, selectors);
    }

    /**
     * 通过临时浏览器会话执行 DOM 登录验证（辩论前预检）。
     */
    private boolean verifyLoginWithBrowser(AiPlatform platform, SelectorProvider selectors) {
        BrowserContext context = null;
        try {
            context = playwrightManager.launchPersistentContextForSetup(
                    platform, getProfilePath(platform));
            LoginStatus status = checkLoginStatus(context, platform, selectors);
            if (status != LoginStatus.LOGGED_IN) {
                log.warn("⛔ {} 不具备辩论资格 — 登录验证未通过: {}", platform.name(), status);
                return false;
            }
            log.info("✅ {} 已通过辩论前登录验证", platform.name());
            return true;
        } catch (Exception e) {
            log.warn("⛔ {} 辩论前登录验证异常: {}", platform.name(), e.getMessage());
            updateLoginStatus(platform, LoginStatus.ERROR);
            return false;
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 删除指定平台的 Profile 目录。
     */
    public void deleteProfile(AiPlatform platform) {
        Path profilePath = getProfilePath(platform);
        if (!Files.exists(profilePath)) {
            log.info("{} Profile 目录不存在，无需删除", platform.name());
            return;
        }
        try {
            try (var files = Files.walk(profilePath)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(f -> {
                            try {
                                Files.deleteIfExists(f);
                            } catch (Exception e) {
                                log.warn("删除文件失败: {}", f);
                            }
                        });
            }
            log.info("🗑️ {} Profile 已删除 — {}", platform.name(), profilePath);
            clearLoginStatus(platform);
        } catch (Exception e) {
            log.error("删除 {} Profile 失败: {}", platform.name(), e.getMessage());
        }
    }

    /**
     * 为指定平台启动或获取持久化 BrowserContext。
     */
    public BrowserContext getOrCreateContext(AiPlatform platform, SelectorProvider selectorProvider) {
        BrowserContext existing = playwrightManager.getContext(platform);
        if (existing != null) {
            return existing;
        }
        Path profilePath = getProfilePath(platform);
        return playwrightManager.launchPersistentContext(platform, profilePath);
    }

    /**
     * 为指定平台获取可自动化操作的页面。
     */
    public Page getOrCreatePage(AiPlatform platform, SelectorProvider selectorProvider) {
        return getOrCreateContext(platform, selectorProvider).newPage();
    }

    /**
     * 交互式手动登录 —— 打开可见浏览器，等待用户在平台上完成登录。
     * <p>
     * 流程：
     * <ol>
     *   <li>强制以 Headed 模式启动持久化 BrowserContext</li>
     *   <li>导航到平台首页</li>
     *   <li>轮询检测登录指示器（最长等待 5 分钟）</li>
     *   <li>检测到登录后关闭浏览器，Profile 即已就绪</li>
     * </ol>
     *
     * @param platform         目标平台
     * @param selectorProvider 选择器提供者
     * @param timeoutSeconds   最大等待时间（秒）
     * @return 是否登录成功
     */
    public boolean runInteractiveSetup(AiPlatform platform, SelectorProvider selectorProvider,
                                        int timeoutSeconds) {
        Path profilePath = getProfilePath(platform);
        log.info("🖥️ 为 {} 启动交互式登录 — 浏览器窗口即将打开，请在窗口中手动登录", platform.name());
        log.info("   Profile 路径: {}", profilePath);
        log.info("   最长等待时间: {} 秒", timeoutSeconds);

        BrowserContext context = null;
        Page page = null;
        try {
            context = playwrightManager.launchPersistentContextForSetup(platform, profilePath);
            page = context.newPage();
            page.navigate(selectorProvider.getPlatformUrl());
            page.waitForLoadState();

            log.info("⏳ 等待登录完成 — 请在浏览器中手动登录 {}...", platform.getDomain());

            // 轮询检测登录指示器（不使用 waitFor，避免超时噪音）
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            int elapsed = 0;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(3000);
                    elapsed += 3;

                    // 检测登录指示器是否存在且可见
                    boolean loggedIn = isLoginIndicatorVisible(page, selectorProvider);

                    if (loggedIn) {
                        log.info("✅ {} 登录成功！（等待了 {} 秒）", platform.name(), elapsed);
                        updateLoginStatus(platform, LoginStatus.LOGGED_IN);
                        return true;
                    }

                    if (elapsed % 15 == 0) {
                        log.info("   仍在等待 {} 登录... (已等待 {} 秒，浏览器窗口应已打开)",
                                platform.name(), elapsed);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // 超时前最后检测一次
            if (isLoginIndicatorVisible(page, selectorProvider)) {
                log.info("✅ {} 登录成功！（最后一刻检测到）", platform.name());
                updateLoginStatus(platform, LoginStatus.LOGGED_IN);
                return true;
            }

            log.warn("⏰ {} 登录超时（{} 秒），请检查浏览器窗口是否仍在等待操作",
                    platform.name(), timeoutSeconds);
            updateLoginStatus(platform, LoginStatus.LOGIN_REQUIRED);
            return false;

        } catch (Exception e) {
            log.error("❌ {} 交互式登录异常: {}", platform.name(), e.getMessage());
            return false;
        } finally {
            if (context != null) {
                try {
                    context.close();
                    log.info("🔒 {} 登录浏览器已关闭", platform.name());
                } catch (Exception e) {
                    log.warn("关闭 {} 登录浏览器失败: {}", platform.name(), e.getMessage());
                }
            }
        }
    }

    /**
     * 综合检测是否已登录（不抛异常，安全返回 false）。
     * <p>
     * 核心策略：
     * <ol>
     *   <li>先用「否定信号」排除登录页面（有登录按钮/注册链接 = 未登录）</li>
     *   <li>再用「肯定信号」确认已登录（聊天专用输入框 / 登录指示器）</li>
     * </ol>
     * 不使用泛型 {@code input[type='text']}——会误匹配登录表单。
     */
    private boolean isLoginIndicatorVisible(Page page, SelectorProvider sp) {
        try {
            String currentUrl = page.url();
            log.debug("检测登录状态: url={}", currentUrl);

            // Step 0：否定信号 — 有登录/注册按钮说明一定没登录
            String[] loginPageIndicators = {
                    "button:has-text('Log in')",
                    "button:has-text('Sign up')",
                    "button:has-text('登录')",
                    "button:has-text('注册')",
                    "a:has-text('Sign up')",
                    "a:has-text('Log in')",
                    "[data-testid='login-button']",
                    "button:has-text('Continue with Google')",
                    "button:has-text('Continue with Apple')",
                    "button:has-text('使用 Google 账户继续')",
                    "button:has-text('使用 Apple 账户继续')",
            };
            for (String sel : loginPageIndicators) {
                try {
                    if (page.locator(sel).count() > 0) {
                        log.debug("检测到登录页元素'{}'，判定未登录", sel);
                        return false;
                    }
                } catch (Exception ignored) {}
            }

            // Step 1：肯定信号 — 平台特有的聊天输入框
            String[] chatInputSelectors = {
                    sp.getInputSelector(),         // 平台专用：如 #prompt-textarea
                    sp.getInputFallbackSelector(), // 平台专用 fallback
            };
            for (String sel : chatInputSelectors) {
                if (sel == null || sel.isBlank()) continue;
                try {
                    if (page.locator(sel).count() > 0) {
                        log.debug("检测到聊天输入框: {}", sel);
                        return true;
                    }
                } catch (Exception ignored) {}
            }

            // Step 2：肯定信号 — 通用聊天输入特征（注意：不用 input[type='text']）
            String[] genericChatSelectors = {
                    "div[contenteditable='true']", // ProseMirror / Quill
                    "textarea",                     // DeepSeek 等标准 textarea
                    "[role='textbox']"              // ARIA 聊天输入框
            };
            // 只有当 URL 明确在聊天页面上时才用泛型选择器
            String platformHost = sp.getPlatformUrl()
                    .replace("https://", "").replace("http://", "").split("/")[0];
            boolean onPlatform = !platformHost.isBlank() && currentUrl.contains(platformHost);
            boolean onAuthPage = currentUrl.contains("accounts.google.com")
                    || currentUrl.contains("/login")
                    || currentUrl.contains("/signin")
                    || currentUrl.contains("/auth");

            if (onPlatform && !onAuthPage) {
                for (String sel : genericChatSelectors) {
                    try {
                        if (page.locator(sel).count() > 0) {
                            log.debug("检测到通用聊天输入框: {}", sel);
                            return true;
                        }
                    } catch (Exception ignored) {}
                }
            }

            // Step 3：肯定信号 — CSS 登录指示器（YAML 配置）
            String[] indicators = {sp.getLoginIndicator(), sp.getLoginIndicatorFallback()};
            for (String indicator : indicators) {
                if (indicator == null || indicator.isBlank()) continue;
                try {
                    if (page.locator(indicator).count() > 0
                            && page.locator(indicator).first().isVisible()) {
                        log.debug("检测到登录指示器: {}", indicator);
                        return true;
                    }
                } catch (Exception ignored) {}
            }

        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 检查所有平台 Profile 的简要状态。
     */
    public String getStatusSummary() {
        StringBuilder sb = new StringBuilder();
        for (AiPlatform p : AiPlatform.values()) {
            boolean ready = isProfileReady(p);
            sb.append(p.name()).append(": ").append(ready ? "已就绪" : "未初始化").append("\n");
        }
        return sb.toString();
    }
}
