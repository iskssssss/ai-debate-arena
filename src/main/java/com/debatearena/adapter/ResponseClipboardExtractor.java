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
 */
@Slf4j
public class ResponseClipboardExtractor {

    private static final int COPY_CLICK_WAIT_MS = 300;
    private static final int CLICK_TIMEOUT_MS = 3000;

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

        if (!clickLastCopyButton(page, chain, platformName)) {
            if (clickCopyButtonViaJs(page, platformName)) {
                log.debug("{} 通过 JS 点击复制按钮", platformName);
            } else {
                return "";
            }
        }

        sleepQuietly(COPY_CLICK_WAIT_MS);
        String text = readClipboard(page);
        if (text != null && !text.isBlank()) {
            log.debug("{} 剪贴板提取成功 ({} 字符)", platformName, text.length());
            return text.trim();
        }

        log.debug("{} 剪贴板为空，剪贴板提取失败", platformName);
        return "";
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
     * JS 兜底：从最后一条 ds-markdown / assistant 消息向上查找复制按钮并点击。
     */
    private boolean clickCopyButtonViaJs(Page page, String platformName) {
        try {
            Object clicked = page.evaluate(
                    "() => { " +
                    "  const md = document.querySelectorAll('div.ds-markdown'); " +
                    "  const anchor = md.length ? md[md.length - 1] " +
                    "    : document.querySelector(\"article[data-testid^='conversation-turn-']:last-of-type\"); " +
                    "  if (!anchor) return false; " +
                    "  let node = anchor; " +
                    "  for (let i = 0; i < 6 && node; i++) { " +
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
