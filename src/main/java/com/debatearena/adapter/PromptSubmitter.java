package com.debatearena.adapter;

import com.debatearena.browser.SelectorProvider;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Supplier;

/**
 * Prompt 提交器 —— 可靠地向各平台输入框发送消息。
 * <p>
 * 策略：
 * <ul>
 *   <li>textarea 输入框：优先 Enter 键发送（比泛化 SVG 按钮更可靠）</li>
 *   <li>contenteditable 输入框：优先点击专用发送按钮，fallback Enter</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class PromptSubmitter {

    private final FallbackSelector fallback;

    /**
     * 填入 Prompt 并触发发送。
     *
     * @param page           页面
     * @param selectors      选择器配置
     * @param prompt         待发送文本
     * @param platformName   平台名称（日志用）
     * @param preferEnterKey 是否优先用 Enter 发送（textarea 平台为 true）
     */
    public void submit(Page page, SelectorProvider selectors, String prompt,
                       String platformName, boolean preferEnterKey) {
        Locator input = fallback.resolveInput(page, selectors.getInputSelectorChain(),
                platformName + " 输入框");
        input.click();
        input.fill(prompt);
        log.debug("已填入 Prompt 到 {} 输入框 ({} 字符)", platformName, prompt.length());

        if (preferEnterKey) {
            page.keyboard().press("Enter");
            log.debug("已通过 Enter 键触发 {} 发送", platformName);
            return;
        }

        var sendButton = fallback.resolveButton(page, selectors.getSubmitSelectorChain(),
                platformName + " 发送按钮");
        if (sendButton != null) {
            sendButton.click();
            log.debug("已点击 {} 发送按钮", platformName);
        } else {
            page.keyboard().press("Enter");
            log.debug("已通过 Enter 键（兜底）触发 {} 发送", platformName);
        }
    }

    /**
     * 仅点击发送按钮（不重新填入，用于 Enter 发送失败后的重试）。
     */
    public void clickSendButton(Page page, SelectorProvider selectors, String platformName) {
        var sendButton = fallback.resolveButton(page, selectors.getSubmitSelectorChain(),
                platformName + " 发送按钮");
        if (sendButton != null) {
            sendButton.click();
            log.debug("已点击 {} 发送按钮（重试）", platformName);
        } else {
            page.keyboard().press("Enter");
            log.debug("已通过 Enter 键（重试）触发 {} 发送", platformName);
        }
    }

    /**
     * 检测是否已开始生成（Stop 按钮出现或输入框被清空）。
     */
    public boolean waitForGenerationStarted(Page page, SelectorProvider selectors, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<String> stopSelectors = List.of(
                selectors.getStopGeneratingSelector(),
                selectors.getStopGeneratingFallback()
        );

        while (System.currentTimeMillis() < deadline) {
            for (String sel : stopSelectors) {
                if (sel != null && fallback.isVisible(page, sel, 500)) {
                    log.debug("检测到生成已开始（Stop 按钮可见）");
                    return true;
                }
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.debug("未检测到 Stop 按钮，可能为快速响应或发送未成功");
        return false;
    }

    /**
     * 等待页面出现比基线更长的新内容（适用于无 Stop 按钮的平台，如 DeepSeek）。
     *
     * @param textSupplier  当前文本提取函数
     * @param baselineLen   发送前的文本长度
     * @param minGrowth     判定为「有新内容」的最小增长字符数
     * @param timeoutMs     超时毫秒
     * @return 是否检测到新内容
     */
    public boolean waitForContentGrowth(Supplier<String> textSupplier, int baselineLen,
                                        int minGrowth, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String current = textSupplier.get();
            int len = current != null ? current.length() : 0;
            if (len >= baselineLen + minGrowth) {
                log.debug("检测到内容增长 ({} → {} 字符)", baselineLen, len);
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
