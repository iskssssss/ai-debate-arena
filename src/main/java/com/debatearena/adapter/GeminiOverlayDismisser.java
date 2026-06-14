package com.debatearena.adapter;

import com.debatearena.browser.SelectorProvider;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Gemini 启动弹窗关闭器 —— 处理页面打开时出现的 discovery-card / cdk-overlay 引导弹窗。
 * <p>
 * 此类弹窗会拦截输入框的 pointer events，导致点击输入框超时失败。
 */
@Slf4j
@RequiredArgsConstructor
public class GeminiOverlayDismisser {

    private static final int MAX_DISMISS_ROUNDS = 3;
    private static final int DISMISS_INTERVAL_MS = 350;

    /**
     * 检测并关闭 Gemini 启动遮罩弹窗（最多重试若干轮，应对多层引导）。
     *
     * @param page       页面
     * @param selectors  选择器配置（overlay-dismiss 链来自 gemini-selectors.yml）
     */
    public void dismissStartupOverlays(Page page, SelectorProvider selectors) {
        if (!hasBlockingOverlay(page)) {
            return;
        }
        log.info("检测到 Gemini 启动弹窗，尝试关闭…");

        for (int round = 1; round <= MAX_DISMISS_ROUNDS; round++) {
            if (!hasBlockingOverlay(page)) {
                log.info("✅ Gemini 启动弹窗已关闭 (round={})", round - 1);
                return;
            }
            dismissOneRound(page, selectors);
            sleepQuietly(DISMISS_INTERVAL_MS);
        }

        if (hasBlockingOverlay(page)) {
            log.warn("⚠️ Gemini 启动弹窗可能仍存在，后续将尝试 JS/force 方式操作输入框");
        } else {
            log.info("✅ Gemini 启动弹窗已关闭");
        }
    }

    /**
     * 执行一轮关闭尝试：YAML 选择器链 → Escape → JS 兜底。
     */
    private void dismissOneRound(Page page, SelectorProvider selectors) {
        for (String selector : selectors.getOverlayDismissSelectorChain()) {
            tryClickDismiss(page, selector);
        }
        pressEscape(page);
        dismissViaJs(page);
    }

    /**
     * 判断是否存在可能遮挡操作的 cdk-overlay / discovery 弹窗。
     */
    private boolean hasBlockingOverlay(Page page) {
        try {
            Locator overlay = page.locator(
                    "div.cdk-overlay-container .discovery-card, "
                            + "div.cdk-overlay-container .cdk-overlay-pane");
            return overlay.count() > 0 && overlay.first().isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 按选择器点击关闭按钮或遮罩背景。
     */
    private void tryClickDismiss(Page page, String selector) {
        if (selector == null || selector.isBlank()) {
            return;
        }
        try {
            Locator target = page.locator(selector).first();
            if (target.count() == 0 || !target.isVisible()) {
                return;
            }
            target.scrollIntoViewIfNeeded();
            try {
                target.click(new Locator.ClickOptions().setTimeout(2000).setForce(true));
            } catch (Exception e) {
                target.evaluate("el => el.click()");
            }
            log.debug("已点击 Gemini 弹窗关闭元素: {}", selector);
        } catch (Exception e) {
            log.debug("Gemini 弹窗关闭选择器失败 [{}]: {}", selector, e.getMessage());
        }
    }

    /**
     * 按 Escape 关闭模态层（Angular Material overlay 通用方式）。
     */
    private void pressEscape(Page page) {
        try {
            page.keyboard().press("Escape");
        } catch (Exception e) {
            log.debug("Gemini Escape 关闭弹窗失败: {}", e.getMessage());
        }
    }

    /**
     * JS 兜底：在 cdk-overlay-container 内查找关闭按钮、discovery-card 主按钮或点击 backdrop。
     */
    private void dismissViaJs(Page page) {
        try {
            Object dismissed = page.evaluate(
                    "() => { " +
                    "  const container = document.querySelector('div.cdk-overlay-container'); " +
                    "  if (!container) return false; " +
                    "  const closeSelectors = [ " +
                    "    \"button[aria-label='Close']\", " +
                    "    \"button[aria-label='关闭']\", " +
                    "    \"button[aria-label='Dismiss']\", " +
                    "    \"button[aria-label='Got it']\", " +
                    "    \"button[aria-label='知道了']\" " +
                    "  ]; " +
                    "  for (const sel of closeSelectors) { " +
                    "    const btn = container.querySelector(sel); " +
                    "    if (btn) { btn.click(); return true; } " +
                    "  } " +
                    "  const discovery = container.querySelector('.discovery-card'); " +
                    "  if (discovery) { " +
                    "    const buttons = discovery.querySelectorAll('button'); " +
                    "    if (buttons.length) { buttons[buttons.length - 1].click(); return true; } " +
                    "  } " +
                    "  const backdrop = container.querySelector('.cdk-overlay-backdrop'); " +
                    "  if (backdrop) { backdrop.click(); return true; } " +
                    "  return false; " +
                    "}");
            if (Boolean.TRUE.equals(dismissed)) {
                log.debug("已通过 JS 关闭 Gemini 启动弹窗");
            }
        } catch (Exception e) {
            log.debug("Gemini JS 关闭弹窗失败: {}", e.getMessage());
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
