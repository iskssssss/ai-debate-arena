package com.debatearena.adapter;

import com.debatearena.browser.HealthStatus;
import com.debatearena.browser.SelectorProvider;
import com.debatearena.model.AiPlatform;
import com.microsoft.playwright.Page;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

/**
 * Gemini (gemini.google.com) 平台适配器。
 * <p>
 * 关键细节：
 * <ul>
 *   <li>输入框：Quill 富文本编辑器（div.ql-editor[contenteditable='true']）</li>
 *   <li>完成检测：Stop Button [aria-label='Stop'] 消失 → Copy Button 出现</li>
 *   <li>对话重置：button[aria-label='New chat']</li>
 *   <li>响应提取：动态 CSS 定位器提取最新响应气泡</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class GeminiAdapter implements PlatformAdapter {

    private final SelectorProvider selectors;
    private final FallbackSelector fallback;
    private Page page;
    private boolean initialized = false;
    private PromptSubmitter submitter;
    private ResponseCompletionWaiter completionWaiter;

    @Getter
    private final AiPlatform platform = AiPlatform.GEMINI;

    private void ensureHelpers() {
        if (submitter == null) {
            submitter = new PromptSubmitter(fallback);
            completionWaiter = new ResponseCompletionWaiter(fallback);
        }
    }

    @Override
    public void initialize(Page page) {
        this.page = page;
        log.info("🤖 初始化 Gemini 适配器");
        page.navigate(selectors.getPlatformUrl());
        page.waitForLoadState();
        fallback.resolveInput(page, selectors.getInputSelectorChain(), "Gemini 输入框");
        this.initialized = true;
        log.info("✅ Gemini 适配器已就绪");
    }

    @Override
    public CompletableFuture<String> sendPrompt(String prompt) {
        if (!initialized || page == null) {
            return CompletableFuture.failedFuture(
                    new BrowserAutomationException("Gemini 适配器未初始化"));
        }

        return CompletableFuture.completedFuture(executeSendPrompt(prompt));
    }

    private String executeSendPrompt(String prompt) {
        ensureHelpers();
        String beforeText = extractPageText();

        submitter.submit(page, selectors, prompt, "Gemini", false);
        boolean started = submitter.waitForGenerationStarted(page, selectors, 15000);
        completionWaiter.waitForComplete(page, selectors, "Gemini", started,
                this::extractLatestResponse, null);

        String afterText = extractPageText();
        String response = diffText(beforeText, afterText);
        if (response.isBlank()) {
            response = extractLatestResponse();
        }
        log.info("✅ Gemini 响应已接收 ({} 字符)", response.length());
        return response;
    }

    /**
     * 提取最新 AI 回答气泡（按时间顺序）。
     */
    private String extractLatestResponse() {
        try {
            String selector = selectors.getLatestResponseSelector();
            if (selector == null) return "";
            var elements = page.locator(selector);
            int count = elements.count();
            if (count > 0) {
                return elements.nth(count - 1).innerText();
            }
        } catch (Exception e) {
            log.warn("提取 Gemini 响应失败: {}", e.getMessage());
        }
        return "";
    }

    @Override
    public void resetConversation() {
        if (!initialized || page == null) return;
        log.info("🔄 重置 Gemini 对话…");

        try {
            String newChatSelector = selectors.getNewChatSelector();
            if (newChatSelector != null) {
                var newChatBtn = page.locator(newChatSelector).first();
                newChatBtn.waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(5000));
                if (newChatBtn.isVisible()) {
                    newChatBtn.click();
                    fallback.resolveInput(page, selectors.getInputSelectorChain(), "Gemini 输入框(重置后)");
                    log.info("✅ Gemini 对话已重置");
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("New Chat 主选择器失败，尝试 fallback: {}", e.getMessage());
        }

        try {
            String fallbackSelectorStr = selectors.getNewChatFallbackSelector();
            if (fallbackSelectorStr != null) {
                page.locator(fallbackSelectorStr).first().click();
                fallback.resolveInput(page, selectors.getInputSelectorChain(), "Gemini 输入框(fallback)");
                log.info("✅ Gemini 对话已重置（fallback）");
            }
        } catch (Exception e) {
            log.error("Gemini 对话重置失败: {}", e.getMessage());
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

            boolean loggedIn = fallback.isVisible(page, selectors.getLoginIndicator(), 5000)
                    || fallback.isVisible(page, selectors.getLoginIndicatorFallback(), 5000);

            boolean inputVisible = false;
            try {
                fallback.resolveInput(page, selectors.getInputSelectorChain(), "Gemini 健康检查输入框");
                inputVisible = true;
            } catch (Exception ignored) {
            }

            if (loggedIn && inputVisible) {
                log.info("✅ Gemini 健康检查通过");
                return HealthStatus.HEALTHY;
            } else if (loggedIn) {
                log.warn("⚠️ Gemini 已登录但输入框不可见");
                return HealthStatus.DEGRADED;
            } else {
                log.warn("⚠️ Gemini 未登录或页面异常");
                return HealthStatus.UNHEALTHY;
            }
        } catch (Exception e) {
            log.error("❌ Gemini 健康检查失败: {}", e.getMessage());
            return HealthStatus.UNHEALTHY;
        }
    }

    @Override
    public void close() {
        log.info("🔒 关闭 Gemini 适配器");
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
