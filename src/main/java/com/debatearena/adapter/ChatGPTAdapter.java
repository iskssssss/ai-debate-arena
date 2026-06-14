package com.debatearena.adapter;

import com.debatearena.browser.HealthStatus;
import com.debatearena.browser.SelectorProvider;
import com.debatearena.model.AiPlatform;
import com.debatearena.model.RoundType;
import com.microsoft.playwright.Page;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * ChatGPT (chatgpt.com) 平台适配器。
 * <p>
 * 关键细节：
 * <ul>
 *   <li>输入框：ProseMirror contenteditable div（#prompt-textarea）</li>
 *   <li>完成检测：Stop Button 消失 + 文本稳定性（500ms 轮询，连续 2 次不变）</li>
 *   <li>对话重置：点击 New Chat 按钮，等待输入框重新出现</li>
 *   <li>响应提取：优先点击「复制回复」按钮从剪贴板读取，DOM 为 fallback</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class ChatGPTAdapter implements PlatformAdapter {

    private final SelectorProvider selectors;
    private final FallbackSelector fallback;
    private Page page;
    private boolean initialized = false;
    private PromptSubmitter submitter;
    private ResponseCompletionWaiter completionWaiter;
    private ResponseClipboardExtractor clipboardExtractor;

    @Getter
    private final AiPlatform platform = AiPlatform.CHATGPT;

    /** 延迟初始化提交器与完成等待器。 */
    private void ensureHelpers() {
        if (submitter == null) {
            submitter = new PromptSubmitter(fallback);
            completionWaiter = new ResponseCompletionWaiter(fallback);
            clipboardExtractor = new ResponseClipboardExtractor();
        }
    }

    @Override
    public void initialize(Page page) {
        this.page = page;
        log.info("🤖 初始化 ChatGPT 适配器");
        page.navigate(selectors.getPlatformUrl());
        page.waitForLoadState();
        // 等待输入框就绪
        fallback.resolveInput(page, selectors.getInputSelectorChain(), "ChatGPT 输入框");
        ensureHelpers();
        clipboardExtractor.grantClipboardPermissions(page);
        this.initialized = true;
        log.info("✅ ChatGPT 适配器已就绪");
    }

    @Override
    public CompletableFuture<String> sendPrompt(String prompt) {
        if (!initialized || page == null) {
            return CompletableFuture.failedFuture(
                    new BrowserAutomationException("ChatGPT 适配器未初始化"));
        }

        return CompletableFuture.completedFuture(executeSendPrompt(prompt));
    }

    private String executeSendPrompt(String prompt) {
        ensureHelpers();
        String beforeText = extractPageText();

        submitter.submit(page, selectors, prompt, "ChatGPT", false);
        boolean started = submitter.waitForGenerationStarted(page, selectors, 15000);
        Optional<String> earlyResponse = completionWaiter.waitForComplete(
                page, selectors, "ChatGPT", started, this::extractLatestResponseForPolling,
                () -> clipboardExtractor.extractViaClipboard(page, selectors, "ChatGPT"));

        String response = earlyResponse.orElseGet(() -> {
            String clip = clipboardExtractor.extractViaClipboard(page, selectors, "ChatGPT");
            if (!clip.isBlank()) return clip;
            String afterText = extractPageText();
            String diff = diffText(beforeText, afterText);
            return diff.isBlank() ? extractLatestResponseFromDom() : diff;
        });
        response = ensureValidResponse(response, prompt);
        log.info("✅ ChatGPT 响应已接收 ({} 字符)", response.length());
        return response;
    }

    /**
     * 校验提取结果；无效时回退到 assistant DOM 重提取，避免误抓用户消息或宿主机剪贴板污染。
     */
    private String ensureValidResponse(String response, String prompt) {
        RoundType roundType = ResponseContentValidator.inferRoundType(prompt);
        String topic = ResponseContentValidator.extractTopicFromPrompt(prompt);
        if (ResponseContentValidator.isValid(response, prompt, topic, roundType)) {
            return response;
        }
        log.warn("ChatGPT 提取结果无效（{} 字符），尝试从 assistant DOM 重提取", response.length());
        String dom = extractLatestResponseFromDom();
        if (ResponseContentValidator.isValid(dom, prompt, topic, roundType)) {
            log.info("ChatGPT DOM 重提取成功 ({} 字符)", dom.length());
            return dom;
        }
        log.warn("ChatGPT DOM 重提取仍无效，保留原结果供上层判定");
        return response;
    }

    /**
     * 提取最新 AI 回答文本（仅 DOM，供完成检测轮询使用，避免重复点击复制按钮）。
     */
    private String extractLatestResponseForPolling() {
        return extractLatestResponseFromDom();
    }

    /**
     * 从 DOM 提取最新 AI 回答（剪贴板失败时的 fallback）。
     */
    private String extractLatestResponseFromDom() {
        try {
            // 策略1：YAML 配置的 latest-response（通常为最后一条 assistant 消息）
            String selector = selectors.getLatestResponseSelector();
            if (selector != null && !selector.isBlank()) {
                var elements = page.locator(selector);
                int count = elements.count();
                if (count > 0) {
                    String text = elements.nth(count - 1).innerText();
                    if (text != null && text.length() > 20) return text;
                }
            }
            // 策略2：所有 assistant 消息取最后一条
            var assistants = page.locator("div[data-message-author-role='assistant']");
            int aCount = assistants.count();
            if (aCount > 0) {
                String text = assistants.nth(aCount - 1).innerText();
                if (text != null && text.length() > 20) return text;
            }
        } catch (Exception e) {
            log.warn("提取 ChatGPT 响应失败: {}", e.getMessage());
        }
        return "";
    }

    @Override
    public void resetConversation() {
        if (!initialized || page == null) return;
        log.info("🔄 重置 ChatGPT 对话…");

        // 点击 New Chat 按钮
        try {
            String newChatSelector = selectors.getNewChatSelector();
            if (newChatSelector != null) {
                var newChatBtn = page.locator(newChatSelector).first();
                newChatBtn.waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(5000));
                if (newChatBtn.isVisible()) {
                    newChatBtn.click();
                    // 等待输入框重新出现
                    fallback.resolveInput(page, selectors.getInputSelectorChain(), "ChatGPT 输入框(重置后)");
                    log.info("✅ ChatGPT 对话已重置");
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("New Chat 主选择器失败，尝试 fallback: {}", e.getMessage());
        }

        // Fallback：使用键盘快捷键 Ctrl+K 或点击首页链接
        try {
            String fallbackSelectorStr = selectors.getNewChatFallbackSelector();
            if (fallbackSelectorStr != null) {
                page.locator(fallbackSelectorStr).first().click();
                fallback.resolveInput(page, selectors.getInputSelectorChain(), "ChatGPT 输入框(fallback)");
                log.info("✅ ChatGPT 对话已重置（fallback）");
            }
        } catch (Exception e) {
            log.error("ChatGPT 对话重置失败: {}", e.getMessage());
        }
    }

    @Override
    public HealthStatus healthCheck() {
        if (!initialized || page == null) {
            return HealthStatus.UNHEALTHY;
        }
        try {
            page.navigate(selectors.getPlatformUrl());
            page.waitForLoadState();

            // 检测登录指示器
            boolean loggedIn = fallback.isVisible(page, selectors.getLoginIndicator(), 5000)
                    || fallback.isVisible(page, selectors.getLoginIndicatorFallback(), 5000);

            // 检测输入框
            boolean inputVisible = false;
            try {
                fallback.resolveInput(page, selectors.getInputSelectorChain(), "ChatGPT 健康检查输入框");
                inputVisible = true;
            } catch (Exception ignored) {
                // 输入框不可见
            }

            if (loggedIn && inputVisible) {
                log.info("✅ ChatGPT 健康检查通过");
                return HealthStatus.HEALTHY;
            } else if (loggedIn) {
                log.warn("⚠️ ChatGPT 已登录但输入框不可见");
                return HealthStatus.DEGRADED;
            } else {
                log.warn("⚠️ ChatGPT 未登录或页面异常");
                return HealthStatus.UNHEALTHY;
            }
        } catch (Exception e) {
            log.error("❌ ChatGPT 健康检查失败: {}", e.getMessage());
            return HealthStatus.UNHEALTHY;
        }
    }

    @Override
    public void close() {
        log.info("🔒 关闭 ChatGPT 适配器");
        initialized = false;
        page = null;
    }

    private String extractPageText() {
        try {
            return (String) page.evaluate(
                "() => { " +
                "  const main = document.querySelector('main') || document.querySelector('[role=\"main\"]') || document.body; " +
                "  return main.innerText || ''; " +
                "}"
            );
        } catch (Exception e) { return ""; }
    }

    private String diffText(String before, String after) {
        if (before == null || before.isBlank()) return after != null ? after : "";
        if (after == null || after.isBlank()) return "";
        int idx = after.indexOf(before);
        if (idx >= 0) return after.substring(idx + before.length()).trim();
        int commonLen = 0, maxLen = Math.min(before.length(), after.length());
        while (commonLen < maxLen && before.charAt(commonLen) == after.charAt(commonLen)) commonLen++;
        if (commonLen > 100) return after.substring(commonLen).trim();
        return after.trim();
    }
}
