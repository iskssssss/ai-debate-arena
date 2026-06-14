package com.debatearena.adapter;

import com.debatearena.browser.SelectorProvider;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 响应剪贴板提取器 —— 通过点击「复制回答」按钮从剪贴板读取最新 AI 回复。
 * <p>
 * 比 DOM innerText 更可靠，可获取完整 Markdown 格式内容。
 * <p>
 * 注意：系统剪贴板为全局共享资源，宿主机操作人员手动复制可能污染读取结果。
 * 本类通过「点击前快照 → 点击后比对 → 未变化则重试」降低误读风险；
 * 若仍失败，调用方应回退到 DOM 提取。
 */
@Slf4j
public class ResponseClipboardExtractor {

    private static final int COPY_CLICK_WAIT_MS = 400;
    private static final int CLICK_TIMEOUT_MS = 3000;
    /** 剪贴板未更新时的最大重试次数。 */
    private static final int MAX_CLIPBOARD_ATTEMPTS = 3;

    /**
     * 为页面授予剪贴板读写权限（需在平台页面加载后调用）。
     */
    public void grantClipboardPermissions(Page page) {
        try {
            URI uri = URI.create(page.url());
            String origin = uri.getScheme() + "://" + uri.getHost();
            page.context().grantPermissions(
                    List.of("clipboard-read", "clipboard-write"),
                    new BrowserContext.GrantPermissionsOptions().setOrigin(origin));
            log.debug("已授予剪贴板权限 — origin={}", origin);
        } catch (Exception e) {
            log.warn("剪贴板权限授予失败: {}", e.getMessage());
        }
    }

    /**
     * 点击最新回复的复制按钮，从剪贴板读取文本。
     * <p>
     * 点击前记录剪贴板快照，点击后验证内容是否发生变化；
     * 若未变化则重试，避免读到宿主机上残留的旧复制内容。
     *
     * @param page         页面
     * @param selectors    选择器配置
     * @param platformName 平台名称（日志用）
     * @return 剪贴板文本，失败时返回空字符串
     */
    public String extractViaClipboard(Page page, SelectorProvider selectors, String platformName) {
        List<String> chain = buildCopySelectorChain(selectors);
        if (chain.isEmpty()) {
            log.debug("{} 未配置复制按钮选择器，跳过剪贴板提取", platformName);
            return "";
        }

        for (int attempt = 1; attempt <= MAX_CLIPBOARD_ATTEMPTS; attempt++) {
            String beforeClip = normalizeClipboard(readClipboard(page));

            if (!clickCopyButton(page, chain, platformName)) {
                log.debug("{} 第 {} 次未能点击复制按钮", platformName, attempt);
                continue;
            }

            sleepQuietly(COPY_CLICK_WAIT_MS);
            String afterClip = normalizeClipboard(readClipboard(page));

            if (afterClip.isBlank()) {
                log.debug("{} 第 {} 次剪贴板为空", platformName, attempt);
                continue;
            }

            if (isSameClipboardContent(beforeClip, afterClip)) {
                log.warn("{} 第 {} 次复制后剪贴板未变化（{} 字符），疑似复制失败或宿主机剪贴板干扰，重试…",
                        platformName, attempt, afterClip.length());
                sleepQuietly(200);
                continue;
            }

            log.debug("{} 剪贴板提取成功 ({} 字符, attempt={})", platformName, afterClip.length(), attempt);
            return afterClip;
        }

        log.warn("{} 剪贴板提取失败：{} 次尝试后仍无法获得点击后的新内容，将回退 DOM 提取",
                platformName, MAX_CLIPBOARD_ATTEMPTS);
        return "";
    }

    /**
     * 点击复制按钮（选择器链优先，JS 兜底）。
     */
    private boolean clickCopyButton(Page page, List<String> chain, String platformName) {
        if (clickLastCopyButton(page, chain, platformName)) {
            return true;
        }
        if (clickCopyButtonViaJs(page, platformName)) {
            log.debug("{} 通过 JS 点击复制按钮", platformName);
            return true;
        }
        return false;
    }

    /**
     * 判断两次剪贴板内容是否实质相同（用于检测复制是否生效）。
     */
    private boolean isSameClipboardContent(String before, String after) {
        if (before.isBlank() && after.isBlank()) {
            return true;
        }
        if (before.isBlank()) {
            return false;
        }
        return before.equals(after);
    }

    /**
     * 归一化剪贴板文本便于比较。
     */
    private String normalizeClipboard(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * 构建复制按钮选择器链（专用字段优先，回退到完成指示器）。
     */
    private List<String> buildCopySelectorChain(SelectorProvider selectors) {
        List<String> chain = new ArrayList<>();
        addIfPresent(chain, selectors.getCopyResponseSelector());
        addIfPresent(chain, selectors.getCopyResponseFallbackSelector());
        // 兼容旧配置：response-wait-indicator 通常也是复制按钮
        addIfPresent(chain, selectors.getResponseWaitIndicator());
        return chain;
    }

    /**
     * 点击最后一个可见的复制按钮（对应最新一条 AI 回复）。
     */
    private boolean clickLastCopyButton(Page page, List<String> selectorChain, String platformName) {
        for (String selector : selectorChain) {
            if (selector == null || selector.isBlank()) continue;
            try {
                Locator button = resolveCopyButton(page, selector);
                if (button == null) {
                    log.debug("{} 复制按钮未找到: {}", platformName, selector);
                    continue;
                }
                if (tryClickCopyButton(button, platformName, selector)) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("{} 复制按钮点击失败 [{}]: {}", platformName, selector, e.getMessage());
            }
        }
        log.debug("{} 所有复制按钮选择器均未匹配", platformName);
        return false;
    }

    /**
     * 解析复制按钮 Locator：已含 :last-of-type 的选择器取首个匹配，否则取最后一个。
     */
    private Locator resolveCopyButton(Page page, String selector) {
        Locator buttons = page.locator(selector);
        int count = buttons.count();
        if (count == 0) return null;
        if (selector.contains(":last-of-type") || selector.contains(":last-child")) {
            return buttons.first();
        }
        return buttons.nth(count - 1);
    }

    /**
     * 尝试点击复制按钮：先滚动到可见区域，普通点击失败后 force 点击。
     */
    private boolean tryClickCopyButton(Locator button, String platformName, String selector) {
        try {
            button.waitFor(new Locator.WaitForOptions().setTimeout(5000));
            button.scrollIntoViewIfNeeded();
            try {
                button.click(new Locator.ClickOptions().setTimeout(CLICK_TIMEOUT_MS));
            } catch (Exception e) {
                log.debug("{} 普通点击失败，尝试 force 点击: {}", platformName, e.getMessage());
                button.click(new Locator.ClickOptions().setTimeout(CLICK_TIMEOUT_MS).setForce(true));
            }
            log.debug("{} 已点击复制按钮 (selector={})", platformName, selector);
            return true;
        } catch (Exception e) {
            log.debug("{} 复制按钮点击失败 [{}]: {}", platformName, selector, e.getMessage());
            return false;
        }
    }

    /**
     * JS 兜底：从最后一条 assistant / ds-markdown 消息向上查找复制按钮并点击。
     */
    private boolean clickCopyButtonViaJs(Page page, String platformName) {
        try {
            Object clicked = page.evaluate(
                    "() => { " +
                    "  const assistants = document.querySelectorAll(\"div[data-message-author-role='assistant']\"); " +
                    "  const anchor = assistants.length ? assistants[assistants.length - 1] " +
                    "    : document.querySelectorAll('div.ds-markdown')[document.querySelectorAll('div.ds-markdown').length - 1]; " +
                    "  if (!anchor) return false; " +
                    "  let node = anchor; " +
                    "  for (let i = 0; i < 8 && node; i++) { " +
                    "    const btn = node.querySelector(" +
                    "      \"button[data-testid='copy-turn-action-button'], " +
                    "       div[role='button'].ds-button--icon\"); " +
                    "    if (btn) { btn.click(); return true; } " +
                    "    node = node.parentElement; " +
                    "  } " +
                    "  return false; " +
                    "}");
            return Boolean.TRUE.equals(clicked);
        } catch (Exception e) {
            log.debug("{} JS 复制按钮点击失败: {}", platformName, e.getMessage());
            return false;
        }
    }

    /**
     * 从浏览器剪贴板异步读取文本。
     */
    private String readClipboard(Page page) {
        try {
            Object result = page.evaluate(
                    "async () => { " +
                    "  try { return await navigator.clipboard.readText(); } " +
                    "  catch (e) { return ''; } " +
                    "}");
            return result != null ? result.toString() : "";
        } catch (Exception e) {
            log.debug("读取剪贴板失败: {}", e.getMessage());
            return "";
        }
    }

    private void addIfPresent(List<String> chain, String selector) {
        if (selector != null && !selector.isBlank() && !chain.contains(selector)) {
            chain.add(selector);
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
