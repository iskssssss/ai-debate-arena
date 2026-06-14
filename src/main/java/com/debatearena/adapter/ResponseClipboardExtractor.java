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

    private static final int COPY_CLICK_WAIT_MS = 500;
    /** Gemini 剪贴板写入较慢，使用更长等待。 */
    private static final int GEMINI_COPY_CLICK_WAIT_MS = 900;
    private static final int CLICK_TIMEOUT_MS = 3000;
    /** 剪贴板未更新时的最大重试次数。 */
    private static final int MAX_CLIPBOARD_ATTEMPTS = 3;
    /** 提取前写入剪贴板的占位标记，用于隔离串行模式下跨平台残留。 */
    private static final String CLIPBOARD_RESET_MARKER = "__DEBATE_ARENA_CLIP_RESET__";

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

        resetClipboard(page, platformName);

        for (int attempt = 1; attempt <= MAX_CLIPBOARD_ATTEMPTS; attempt++) {
            String beforeClip = normalizeClipboard(readClipboard(page));

            if (!clickCopyButton(page, chain, platformName)) {
                log.debug("{} 第 {} 次未能点击复制按钮", platformName, attempt);
                continue;
            }

            sleepQuietly(resolveCopyClickWaitMs(platformName, attempt));
            String afterClip = normalizeClipboard(readClipboard(page));

            if (afterClip.isBlank()) {
                log.debug("{} 第 {} 次剪贴板为空", platformName, attempt);
                continue;
            }

            if (isResetMarker(afterClip)) {
                log.debug("{} 第 {} 次复制后仍为占位标记，重试…", platformName, attempt);
                sleepQuietly(200);
                continue;
            }

            if (isSameClipboardContent(beforeClip, afterClip)) {
                log.warn("{} 第 {} 次复制后剪贴板未变化（{} 字符），疑似复制失败或宿主机剪贴板干扰，重试…",
                        platformName, attempt, afterClip.length());
                resetClipboard(page, platformName);
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
     * 提取前重置剪贴板，避免串行模式下读到上一平台的复制内容。
     */
    private void resetClipboard(Page page, String platformName) {
        try {
            page.evaluate(
                    "async (marker) => { " +
                    "  try { await navigator.clipboard.writeText(marker); } " +
                    "  catch (e) { /* ignore */ } " +
                    "}", CLIPBOARD_RESET_MARKER);
            log.debug("{} 已重置剪贴板占位", platformName);
        } catch (Exception e) {
            log.debug("{} 重置剪贴板失败: {}", platformName, e.getMessage());
        }
    }

    /**
     * 判断剪贴板内容是否为提取前写入的占位标记。
     */
    private boolean isResetMarker(String text) {
        return CLIPBOARD_RESET_MARKER.equals(text != null ? text.trim() : "");
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
     * 按平台与重试次数计算复制后等待时长，Gemini 给予更长缓冲。
     */
    private long resolveCopyClickWaitMs(String platformName, int attempt) {
        int base = "Gemini".equals(platformName) ? GEMINI_COPY_CLICK_WAIT_MS : COPY_CLICK_WAIT_MS;
        return base + (long) (attempt - 1) * 350;
    }

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
     * 构建复制按钮选择器链（使用 YAML 配置的完整 fallback 链）。
     */
    private List<String> buildCopySelectorChain(SelectorProvider selectors) {
        return new ArrayList<>(selectors.getCopyResponseSelectorChain());
    }

    /**
     * 点击最后一个可见的复制按钮（对应最新一条 AI 回复）。
     * 点击前先滚动消息到视口中部并悬停，避免 ChatGPT 底栏 sticky 区域拦截 pointer events。
     */
    private boolean clickLastCopyButton(Page page, List<String> selectorChain, String platformName) {
        prepareLatestMessageForCopy(page, platformName);

        for (String selector : selectorChain) {
            if (selector == null || selector.isBlank()) continue;
            try {
                Locator button = resolveCopyButton(page, selector);
                if (button == null) {
                    log.debug("{} 复制按钮未找到: {}", platformName, selector);
                    continue;
                }
                if (tryClickCopyButton(page, button, platformName, selector)) {
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
     * 将最新 assistant 消息滚到视口中部并悬停，使操作栏复制按钮露出且远离底部输入框遮挡。
     */
    private void prepareLatestMessageForCopy(Page page, String platformName) {
        try {
            page.evaluate(
                    "() => { " +
                    "  const ds = document.querySelectorAll('div.ds-markdown'); " +
                    "  const dsLast = ds.length ? ds[ds.length - 1] : null; " +
                    "  const assistants = document.querySelectorAll(\"div[data-message-author-role='assistant']\"); " +
                    "  const modelResponses = document.querySelectorAll('model-response'); " +
                    "  const modelLast = modelResponses.length ? modelResponses[modelResponses.length - 1] : null; " +
                    "  const geminiResp = document.querySelectorAll('div.response-content'); " +
                    "  const geminiRespLast = geminiResp.length ? geminiResp[geminiResp.length - 1] : null; " +
                    "  const geminiMsgs = document.querySelectorAll('message-content'); " +
                    "  const geminiLast = geminiMsgs.length ? geminiMsgs[geminiMsgs.length - 1] : null; " +
                    "  const anchor = dsLast || modelLast || geminiRespLast || geminiLast "
                    + "|| (assistants.length ? assistants[assistants.length - 1] : null); " +
                    "  if (!anchor) return; " +
                    "  anchor.scrollIntoView({ block: 'center', behavior: 'instant' }); " +
                    "  anchor.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true })); " +
                    "  anchor.dispatchEvent(new MouseEvent('mouseover', { bubbles: true })); " +
                    "}");
            sleepQuietly(350);
        } catch (Exception e) {
            log.debug("{} 准备复制目标消息失败: {}", platformName, e.getMessage());
        }
    }

    /**
     * 解析复制按钮 Locator：优先取最后一条 assistant 消息内的按钮，否则取选择器匹配的最后一个。
     */
    private Locator resolveCopyButton(Page page, String selector) {
        Locator scopedInLastAssistant = resolveCopyButtonInLastAssistant(page);
        if (scopedInLastAssistant != null) {
            return scopedInLastAssistant;
        }

        Locator scopedInLastDeepSeek = resolveCopyButtonInLastDeepSeek(page);
        if (scopedInLastDeepSeek != null) {
            return scopedInLastDeepSeek;
        }

        Locator scopedInLastGemini = resolveCopyButtonInLastGemini(page);
        if (scopedInLastGemini != null) {
            return scopedInLastGemini;
        }

        if (selector != null && selector.contains("ds-markdown") && !selector.contains("role='button'")) {
            return null;
        }

        Locator buttons = page.locator(selector);
        int count = buttons.count();
        if (count == 0) return null;
        if (selector.contains(":last-of-type") || selector.contains(":last-child")) {
            return buttons.first();
        }
        return buttons.nth(count - 1);
    }

    /**
     * 在最后一条 DeepSeek ds-markdown 消息附近查找复制按钮。
     */
    private Locator resolveCopyButtonInLastDeepSeek(Page page) {
        try {
            Locator markdowns = page.locator("div.ds-markdown");
            int count = markdowns.count();
            if (count == 0) {
                return null;
            }
            Locator lastMd = markdowns.nth(count - 1);
            Locator siblingBtn = lastMd.locator(
                    "xpath=following-sibling::*//div[@role='button' and contains(@class,'ds-button--icon')]");
            if (siblingBtn.count() > 0) {
                return siblingBtn.last();
            }
            Locator parentBtn = lastMd.locator(
                    "xpath=ancestor::*[1]//div[@role='button' and contains(@class,'ds-button--icon')]");
            if (parentBtn.count() > 0) {
                return parentBtn.last();
            }
        } catch (Exception ignored) {
            // 回退到通用选择器解析
        }
        return null;
    }

    /**
     * 在最后一条 Gemini model-response 页脚查找「复制回答」按钮。
     * 复制按钮位于 message-actions/copy-button，不在 message-content 内；
     * 须排除用户提示区的 data-test-id=prompt-copy-button（aria-label=复制提示）。
     */
    private Locator resolveCopyButtonInLastGemini(Page page) {
        try {
            Locator modelResponses = page.locator("model-response");
            int modelCount = modelResponses.count();
            if (modelCount > 0) {
                Locator lastModel = modelResponses.nth(modelCount - 1);
                Locator footerCopy = lastModel.locator(
                        "copy-button[data-test-id='copy-button'] button[aria-label='复制'], "
                                + "message-actions copy-button button[aria-label='复制']");
                if (footerCopy.count() > 0) {
                    return footerCopy.first();
                }
            }

            Locator answerCopyButtons = page.locator(
                    "copy-button[data-test-id='copy-button'] button[aria-label='复制']");
            int copyCount = answerCopyButtons.count();
            if (copyCount > 0) {
                return answerCopyButtons.nth(copyCount - 1);
            }
        } catch (Exception ignored) {
            // 回退到通用选择器解析
        }
        return null;
    }

    /**
     * 在最后一条 assistant 消息所在的对话轮次容器内查找复制按钮。
     */
    private Locator resolveCopyButtonInLastAssistant(Page page) {
        try {
            Locator assistants = page.locator("div[data-message-author-role='assistant']");
            int count = assistants.count();
            if (count == 0) return null;

            Locator lastAssistant = assistants.nth(count - 1);
            Locator inTurn = lastAssistant.locator(
                    "xpath=ancestor::*[self::article or @data-testid][1]"
                            + "//button[@data-testid='copy-turn-action-button']");
            if (inTurn.count() > 0) {
                return inTurn.first();
            }

            Locator nearby = lastAssistant.locator("button[data-testid='copy-turn-action-button']");
            if (nearby.count() > 0) {
                return nearby.first();
            }
        } catch (Exception ignored) {
            // 回退到通用选择器解析
        }
        return null;
    }

    /**
     * 尝试点击复制按钮：优先 JS 原生 click（绕过 sticky 底栏 pointer 拦截），再 Playwright force 点击。
     */
    private boolean tryClickCopyButton(Page page, Locator button, String platformName, String selector) {
        try {
            button.waitFor(new Locator.WaitForOptions().setTimeout(5000));
            button.scrollIntoViewIfNeeded();

            if (clickCopyButtonViaElementJs(button, platformName)) {
                log.debug("{} 已通过 JS 点击复制按钮 (selector={})", platformName, selector);
                return true;
            }

            try {
                button.click(new Locator.ClickOptions().setTimeout(CLICK_TIMEOUT_MS).setForce(true));
            } catch (Exception e) {
                log.debug("{} Playwright force 点击失败: {}", platformName, e.getMessage());
                return false;
            }
            log.debug("{} 已通过 force 点击复制按钮 (selector={})", platformName, selector);
            return true;
        } catch (Exception e) {
            log.debug("{} 复制按钮点击失败 [{}]: {}", platformName, selector, e.getMessage());
            return false;
        }
    }

    /**
     * 对目标元素执行 JS 原生 click，不受 Playwright pointer events 拦截影响。
     */
    private boolean clickCopyButtonViaElementJs(Locator button, String platformName) {
        try {
            Object clicked = button.evaluate("el => { el.click(); return true; }");
            return Boolean.TRUE.equals(clicked);
        } catch (Exception e) {
            log.debug("{} JS 元素点击失败: {}", platformName, e.getMessage());
            return false;
        }
    }

    /**
     * JS 兜底：优先点击最后一条 model-response 页脚的「复制回答」按钮，排除用户提示的「复制提示」。
     */
    private boolean clickCopyButtonViaJs(Page page, String platformName) {
        try {
            prepareLatestMessageForCopy(page, platformName);
            Object clicked = page.evaluate(
                    "() => { " +
                    "  const pickAnswerCopy = (root) => { " +
                    "    if (!root) return null; " +
                    "    return root.querySelector(" +
                    "      \"copy-button[data-test-id='copy-button'] button[aria-label='复制'], " +
                    "       message-actions copy-button button[aria-label='复制']\"); " +
                    "  }; " +
                    "  const modelResponses = document.querySelectorAll('model-response'); " +
                    "  if (modelResponses.length) { " +
                    "    const lastModel = modelResponses[modelResponses.length - 1]; " +
                    "    lastModel.scrollIntoView({ block: 'center', behavior: 'instant' }); " +
                    "    const geminiBtn = pickAnswerCopy(lastModel); " +
                    "    if (geminiBtn) { geminiBtn.click(); return true; } " +
                    "  } " +
                    "  const allAnswerCopy = document.querySelectorAll(" +
                    "    \"copy-button[data-test-id='copy-button'] button[aria-label='复制']\"); " +
                    "  if (allAnswerCopy.length) { " +
                    "    allAnswerCopy[allAnswerCopy.length - 1].click(); return true; " +
                    "  } " +
                    "  const ds = document.querySelectorAll('div.ds-markdown'); " +
                    "  const dsLast = ds.length ? ds[ds.length - 1] : null; " +
                    "  const assistants = document.querySelectorAll(\"div[data-message-author-role='assistant']\"); " +
                    "  const anchor = dsLast || (assistants.length ? assistants[assistants.length - 1] : null); " +
                    "  if (!anchor) return false; " +
                    "  anchor.scrollIntoView({ block: 'center', behavior: 'instant' }); " +
                    "  let node = anchor; " +
                    "  for (let i = 0; i < 10 && node; i++) { " +
                    "    const btn = node.querySelector(" +
                    "      \"button[data-testid='copy-turn-action-button'], " +
                    "       div[role='button'].ds-button--icon\"); " +
                    "    if (btn) { btn.click(); return true; } " +
                    "    node = node.parentElement; " +
                    "  } " +
                    "  const chatCopy = document.querySelectorAll(" +
                    "    \"button[data-testid='copy-turn-action-button']\"); " +
                    "  if (chatCopy.length) { chatCopy[chatCopy.length - 1].click(); return true; } " +
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


    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
