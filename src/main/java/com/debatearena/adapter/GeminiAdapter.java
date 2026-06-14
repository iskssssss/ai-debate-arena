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
 * Gemini (gemini.google.com) 平台适配器。
 * <p>
 * 关键细节：
 * <ul>
 *   <li>输入框：Quill 富文本编辑器（div.ql-editor[contenteditable='true']）</li>
 *   <li>完成检测：Stop Button 消失 → 复制按钮（aria-label=复制）出现</li>
 *   <li>对话重置：button[aria-label='New chat']</li>
 *   <li>响应提取：优先点击 Material 复制按钮从剪贴板读取，DOM 为 fallback</li>
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
    private ResponseClipboardExtractor clipboardExtractor;
    private GeminiOverlayDismisser overlayDismisser;

    @Getter
    private final AiPlatform platform = AiPlatform.GEMINI;

    private void ensureHelpers() {
        if (submitter == null) {
            submitter = new PromptSubmitter(fallback);
            completionWaiter = new ResponseCompletionWaiter(fallback);
            clipboardExtractor = new ResponseClipboardExtractor();
            overlayDismisser = new GeminiOverlayDismisser();
        }
    }

    /**
     * 关闭启动弹窗并确保输入框可交互（发送/重置/健康检查前调用）。
     */
    private void preparePageForInteraction() {
        ensureHelpers();
        overlayDismisser.dismissStartupOverlays(page, selectors);
    }

    @Override
    public void initialize(Page page) {
        this.page = page;
        log.info("🤖 初始化 Gemini 适配器");
        page.navigate(selectors.getPlatformUrl());
        page.waitForLoadState();
        preparePageForInteraction();
        fallback.resolveInput(page, selectors.getInputSelectorChain(), "Gemini 输入框");
        ensureHelpers();
        clipboardExtractor.grantClipboardPermissions(page);
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
        preparePageForInteraction();
        String beforeText = extractPageText();

        submitter.submit(page, selectors, prompt, "Gemini", false);
        boolean started = submitter.waitForGenerationStarted(page, selectors, 15000);
        Optional<String> earlyResponse = completionWaiter.waitForComplete(
                page, selectors, "Gemini", started, this::extractLatestResponseForPolling,
                () -> clipboardExtractor.extractViaClipboard(page, selectors, "Gemini"));

        String response = earlyResponse.orElseGet(() -> {
            String clip = clipboardExtractor.extractViaClipboard(page, selectors, "Gemini");
            if (!clip.isBlank()) {
                return clip;
            }
            waitForDomContentReady();
            String afterText = extractPageText();
            String diff = diffText(beforeText, afterText);
            return diff.isBlank() ? extractLatestResponseFromDom() : diff;
        });
        response = ResponseContentValidator.sanitizePlatformUiArtifacts(response);
        response = ensureValidResponse(response, prompt);
        log.info("✅ Gemini 响应已接收 ({} 字符)", response.length());
        return response;
    }

    /**
     * 校验提取结果；无效时回退到 DOM 重提取。
     */
    private String ensureValidResponse(String response, String prompt) {
        RoundType roundType = ResponseContentValidator.inferRoundType(prompt);
        String topic = ResponseContentValidator.extractTopicFromPrompt(prompt);
        if (ResponseContentValidator.isValid(response, prompt, topic, roundType)) {
            return response;
        }
        log.warn("Gemini 提取结果无效（{} 字符），尝试从 DOM 重提取", response.length());
        waitForDomContentReady();
        String dom = extractLatestResponseFromDom();
        dom = ResponseContentValidator.sanitizePlatformUiArtifacts(dom);
        if (ResponseContentValidator.isValid(dom, prompt, topic, roundType)) {
            log.info("Gemini DOM 重提取成功 ({} 字符)", dom.length());
            return dom;
        }
        log.warn("Gemini DOM 重提取仍无效，保留原结果供上层判定");
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
     * 优先 message-content 内 markdown，避免 response-content 层的 screen-reader「Gemini 说」前缀。
     */
    private String extractLatestResponseFromDom() {
        try {
            var markdowns = page.locator("model-response message-content div.markdown");
            int mdCount = markdowns.count();
            if (mdCount > 0) {
                String text = markdowns.nth(mdCount - 1).innerText();
                if (text != null && text.length() > 20) {
                    return text;
                }
            }
            var allMarkdowns = page.locator("message-content div.markdown");
            int allMdCount = allMarkdowns.count();
            if (allMdCount > 0) {
                String text = allMarkdowns.nth(allMdCount - 1).innerText();
                if (text != null && text.length() > 20) {
                    return text;
                }
            }
            String selector = selectors.getLatestResponseSelector();
            if (selector != null && !selector.isBlank()) {
                var elements = page.locator(selector);
                int count = elements.count();
                if (count > 0) {
                    String text = elements.nth(count - 1).innerText();
                    if (text != null && text.length() > 20) {
                        return text;
                    }
                }
            }
            var responses = page.locator("div.response-content");
            int respCount = responses.count();
            if (respCount > 0) {
                String text = responses.nth(respCount - 1).innerText();
                if (text != null && text.length() > 20) {
                    return text;
                }
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
        preparePageForInteraction();

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
            preparePageForInteraction();

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

    /**
     * 剪贴板失败后等待 DOM 内容稳定，避免流式未完成即提取。
     */
    private void waitForDomContentReady() {
        String last = "";
        int stableCount = 0;
        for (int i = 0; i < 16; i++) {
            String current = extractLatestResponseFromDom();
            if (!current.isBlank() && current.equals(last)) {
                stableCount++;
                if (stableCount >= 3) {
                    log.debug("Gemini DOM 内容已稳定 ({} 字符)", current.length());
                    return;
                }
            } else {
                stableCount = 0;
            }
            last = current;
            sleepQuietly(500);
        }
        log.debug("Gemini DOM 稳定等待结束，继续提取 (lastLen={})", last.length());
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String extractPageText() {
        try {
            return (String) page.evaluate(
                "() => { " +
                "  const md = document.querySelectorAll('model-response message-content div.markdown'); " +
                "  if (md.length) return Array.from(md).map(el => el.innerText).join('\\n'); " +
                "  const allMd = document.querySelectorAll('message-content div.markdown'); " +
                "  if (allMd.length) return Array.from(allMd).map(el => el.innerText).join('\\n'); " +
                "  const resp = document.querySelectorAll('div.response-content'); " +
                "  if (resp.length) return Array.from(resp).map(el => el.innerText).join('\\n'); " +
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
