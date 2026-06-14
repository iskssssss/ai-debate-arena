package com.debatearena.adapter;

import com.debatearena.browser.SelectorProvider;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 响应完成等待器 —— 统一的多级完成检测链（优化版）。
 * <p>
 * 检测顺序：
 * <ol>
 *   <li>Stop 按钮出现后消失（主信号）</li>
 *   <li>Stop 消失后立即尝试剪贴板提取（跳过 60s 指示器空等）</li>
 *   <li>短超时等待完成指示器（默认 8s）</li>
 *   <li>再次尝试剪贴板</li>
 *   <li>有限次文本稳定性轮询（最多 3s）</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class ResponseCompletionWaiter {

    private static final int STABLE_POLL_MS = 500;
    private static final int MAX_STABLE_POLLS = 6;
    private static final int INDICATOR_WAIT_MS = 8000;
    private static final int MIN_EARLY_EXTRACT_LEN = 50;

    private final FallbackSelector fallback;

    /**
     * 等待 AI 响应生成完成，并尽可能提前通过剪贴板拿到结果。
     *
     * @param earlyClipboardSupplier Stop 消失后用于提前剪贴板提取（可为 null）
     * @return 若提前剪贴板成功则返回文本，否则 empty
     */
    public Optional<String> waitForComplete(Page page, SelectorProvider selectors, String platformName,
                                            boolean generationStarted,
                                            Supplier<String> latestTextSupplier,
                                            Supplier<String> earlyClipboardSupplier) {
        int timeoutMs = selectors.getTimeouts().getOrDefault("response-complete", 120000);

        if (generationStarted) {
            waitForStopButtonGone(page, selectors, timeoutMs, platformName);
            Optional<String> early = tryEarlyClipboard(earlyClipboardSupplier, platformName, "Stop 消失后");
            if (early.isPresent()) {
                return early;
            }
        }

        int indicatorWait = selectors.getTimeouts().getOrDefault("indicator-wait", INDICATOR_WAIT_MS);
        waitForCompletionIndicator(page, selectors, platformName, indicatorWait);

        Optional<String> afterIndicator = tryEarlyClipboard(earlyClipboardSupplier, platformName, "指示器后");
        if (afterIndicator.isPresent()) {
            return afterIndicator;
        }

        sleepQuietly(300);
        waitForTextStable(latestTextSupplier, platformName);
        return Optional.empty();
    }

    /**
     * 等待 Stop 按钮从可见变为隐藏。
     */
    private void waitForStopButtonGone(Page page, SelectorProvider selectors,
                                       int timeoutMs, String platformName) {
        List<String> stopSelectors = List.of(
                selectors.getStopGeneratingSelector(),
                selectors.getStopGeneratingFallback()
        );
        for (String sel : stopSelectors) {
            if (sel == null || sel.isBlank()) continue;
            log.debug("等待 {} Stop 按钮消失… selector={}", platformName, sel);
            if (fallback.waitForDisappear(page, sel, timeoutMs)) {
                log.debug("{} Stop 按钮已消失", platformName);
                return;
            }
        }
        log.debug("{} Stop 按钮未消失或不存在，继续后续检测", platformName);
    }

    /**
     * 短超时等待完成指示器出现（如 Copy 按钮）。
     */
    private void waitForCompletionIndicator(Page page, SelectorProvider selectors,
                                            String platformName, int waitMs) {
        String indicator = selectors.getResponseWaitIndicator();
        if (indicator == null || indicator.isBlank()) {
            return;
        }
        log.debug("等待 {} 完成指示器出现（{}ms）… selector={}", platformName, waitMs, indicator);
        if (fallback.waitForSelector(page, indicator, waitMs)) {
            log.debug("{} 完成指示器已出现", platformName);
        } else {
            log.debug("{} 完成指示器未在 {}ms 内出现，继续剪贴板/DOM 提取", platformName, waitMs);
        }
    }

    /**
     * 有限次文本稳定性检测：连续 2 次相同且非空则完成。
     */
    private void waitForTextStable(Supplier<String> latestTextSupplier, String platformName) {
        log.debug("{} 文本稳定性检测…", platformName);
        String lastContent = "";
        int stableCount = 0;

        for (int i = 0; i < MAX_STABLE_POLLS && stableCount < 2; i++) {
            sleepQuietly(STABLE_POLL_MS);
            String current = latestTextSupplier.get();
            if (current != null && !current.isBlank() && current.equals(lastContent)) {
                stableCount++;
            } else {
                stableCount = 0;
            }
            lastContent = current != null ? current : "";
        }
        log.debug("{} 文本稳定性检测完成 (stableCount={}, lastLen={})",
                platformName, stableCount, lastContent.length());
    }

    /**
     * 尝试通过剪贴板提前拿到完整响应，成功则跳过后续长等待。
     */
    private Optional<String> tryEarlyClipboard(Supplier<String> supplier, String platformName, String stage) {
        if (supplier == null) {
            return Optional.empty();
        }
        try {
            String text = supplier.get();
            if (text != null && text.length() >= MIN_EARLY_EXTRACT_LEN) {
                log.debug("{} {} 剪贴板提前提取成功 ({} 字符)", platformName, stage, text.length());
                return Optional.of(text.trim());
            }
        } catch (Exception e) {
            log.debug("{} {} 剪贴板提前提取失败: {}", platformName, stage, e.getMessage());
        }
        return Optional.empty();
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
