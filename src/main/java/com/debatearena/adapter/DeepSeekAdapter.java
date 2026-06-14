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
 * DeepSeek (chat.deepseek.com) 平台适配器。
 * <p>
 * 关键细节：
 * <ul>
 *   <li>输入框：标准 textarea（#chat-input），三平台中最稳定</li>
 *   <li>完成检测：Stop Button 消失 → 文本稳定性</li>
 *   <li>对话重置：button[class*='new-chat']</li>
 *   <li>响应提取：优先点击复制按钮从剪贴板读取，DOM 为 fallback</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class DeepSeekAdapter implements PlatformAdapter {

    private final SelectorProvider selectors;
    private final FallbackSelector fallback;
    private Page page;
    private boolean initialized = false;
    private PromptSubmitter submitter;
    private ResponseCompletionWaiter completionWaiter;
    private ResponseClipboardExtractor clipboardExtractor;

    @Getter
    private final AiPlatform platform = AiPlatform.DEEPSEEK;

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
        log.info("🤖 初始化 DeepSeek 适配器");
        page.navigate(selectors.getPlatformUrl());
        page.waitForLoadState();
        fallback.resolveInput(page, selectors.getInputSelectorChain(), "DeepSeek 输入框");
        ensureHelpers();
        clipboardExtractor.grantClipboardPermissions(page);
        this.initialized = true;
        log.info("✅ DeepSeek 适配器已就绪");
    }

    @Override
    public CompletableFuture<String> sendPrompt(String prompt) {
        if (!initialized || page == null) {
            return CompletableFuture.failedFuture(
                    new BrowserAutomationException("DeepSeek 适配器未初始化"));
        }

        return CompletableFuture.completedFuture(executeSendPrompt(prompt));
    }

    private String executeSendPrompt(String prompt) {
        ensureHelpers();
        ensurePageReady();
        String beforeText = extractPageText();

        int baselineLen = beforeText.length();

        // DeepSeek 使用 textarea，Enter 发送最可靠
        submitter.submit(page, selectors, prompt, "DeepSeek", true);
        boolean started = submitter.waitForGenerationStarted(page, selectors, 5000);
        if (!started) {
            started = submitter.waitForContentGrowth(this::extractLatestResponseFromDom, baselineLen, 30, 8000);
        }
        if (!started) {
            log.warn("DeepSeek Enter 未触发发送，尝试点击发送按钮…");
            submitter.clickSendButton(page, selectors, "DeepSeek");
            started = submitter.waitForGenerationStarted(page, selectors, 5000)
                    || submitter.waitForContentGrowth(this::extractLatestResponseFromDom, baselineLen, 30, 8000);
        }

        Optional<String> earlyResponse = completionWaiter.waitForComplete(
                page, selectors, "DeepSeek", started, this::extractLatestResponseForPolling,
                () -> clipboardExtractor.extractViaClipboard(page, selectors, "DeepSeek"));

        String response = earlyResponse.orElseGet(() -> {
            String clip = clipboardExtractor.extractViaClipboard(page, selectors, "DeepSeek");
            if (!clip.isBlank()) return clip;
            String stableDom = extractLatestResponseWithStability();
            if (!stableDom.isBlank()) return stableDom;
            String afterText = extractPageText();
            String diff = diffText(beforeText, afterText);
            return diff.isBlank() ? extractLatestResponseFromDom() : diff;
        });
        response = ensureValidResponse(response, prompt);
        log.info("✅ DeepSeek 响应已接收 ({} 字符)", response.length());
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
        log.warn("DeepSeek 提取结果无效（{} 字符），尝试从 assistant DOM 重提取", response.length());
        String dom = extractLatestResponseFromDom();
        if (ResponseContentValidator.isValid(dom, prompt, topic, roundType)) {
            log.info("DeepSeek DOM 重提取成功 ({} 字符)", dom.length());
            return dom;
        }
        log.warn("DeepSeek DOM 重提取仍无效，保留原结果供上层判定");
        return response;
    }

    /**
     * 发送前确保页面加载完成且输入框可见，并行启动时页面可能尚未稳定。
     */
    private void ensurePageReady() {
        page.waitForLoadState();
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                fallback.resolveInput(page, selectors.getInputSelectorChain(), "DeepSeek 输入框");
                return;
            } catch (Exception e) {
                if (attempt >= maxRetries) {
                    throw e;
                }
                log.warn("DeepSeek 输入框未就绪，重试 {}/{}…", attempt, maxRetries);
                page.waitForTimeout(1000);
                page.navigate(selectors.getPlatformUrl());
                page.waitForLoadState();
            }
        }
    }

    /**
     * 提取最新 AI 回答文本（仅 DOM，供完成检测轮询使用，避免重复点击复制按钮）。
     */
    private String extractLatestResponseForPolling() {
        return extractLatestResponseFromDom();
    }

    /**
     * 轮询 DOM 直到文本连续稳定，避免流式输出未完成时过早截断。
     */
    private String extractLatestResponseWithStability() {
        String last = "";
        int stableCount = 0;
        for (int i = 0; i < 12 && stableCount < 2; i++) {
            sleepQuietly(500);
            String current = extractLatestResponseFromDom();
            if (!current.isBlank() && current.equals(last)) {
                stableCount++;
            } else {
                stableCount = 0;
            }
            last = current;
        }
        if (!last.isBlank()) {
            log.debug("DeepSeek DOM 稳定提取完成 ({} 字符, stableCount={})", last.length(), stableCount);
        }
        return last;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 从 DOM 提取最新 AI 回答（剪贴板失败时的 fallback）。
     * <p>
     * DeepSeek 使用 CSS Modules 动态类名，需多级 fallback：
     * <ol>
     *   <li>ds-markdown（DeepSeek 的 Markdown 渲染容器）</li>
     *   <li>YAML 配置的 latest-response 选择器</li>
     *   <li>按 DOM 结构定位最后一个消息块</li>
     * </ol>
     */
    private String extractLatestResponseFromDom() {
        try {
            // 策略1：ds-markdown 容器（DeepSeek 专用）
            var mdElements = page.locator("div.ds-markdown");
            int mdCount = mdElements.count();
            if (mdCount > 0) {
                String text = mdElements.nth(mdCount - 1).innerText();
                if (isValidResponse(text)) return text;
            }

            // 策略2：YAML 配置的选择器
            String selector = selectors.getLatestResponseSelector();
            if (selector != null && !selector.isBlank()) {
                var elements = page.locator(selector);
                int count = elements.count();
                if (count > 0) {
                    String text = elements.nth(count - 1).innerText();
                    if (isValidResponse(text)) return text;
                }
            }

            // 策略3：按结构找最后一个对话气泡（排除输入区）
            var bubbles = page.locator("div[class*='Message'], div[class*='message'], div[class*='bubble']");
            int bubbleCount = bubbles.count();
            if (bubbleCount > 0) {
                String text = bubbles.nth(bubbleCount - 1).innerText();
                if (isValidResponse(text)) return text;
            }

            // 兜底：仅取最后一个 ds-markdown，避免混入侧边栏历史
            var mdOnly = page.locator("div.ds-markdown");
            int mdOnlyCount = mdOnly.count();
            if (mdOnlyCount > 0) {
                String text = mdOnly.nth(mdOnlyCount - 1).innerText();
                if (isValidResponse(text)) return text;
            }

        } catch (Exception e) {
            log.warn("提取 DeepSeek 响应失败: {}", e.getMessage());
        }
        return "";
    }

    /** 验证响应文本是否有效（不是页面模板/HTML dump）。 */
    private boolean isValidResponse(String text) {
        if (text == null || text.isBlank()) return false;
        // 太短的不是有效回答
        if (text.length() < 20) return false;
        // 包含过多 HTML 标签说明选错了元素
        int tagCount = countOccurrences(text, "<div") + countOccurrences(text, "<span");
        int textLen = text.length();
        // 如果每 100 字符就有一个 div/span 标签，很可能是 HTML dump
        return (double) tagCount / textLen * 100 < 2.0;
    }

    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }

    @Override
    public void resetConversation() {
        if (!initialized || page == null) return;
        log.info("🔄 重置 DeepSeek 对话…");

        try {
            String newChatSelector = selectors.getNewChatSelector();
            if (newChatSelector != null) {
                var newChatBtn = page.locator(newChatSelector).first();
                newChatBtn.waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(5000));
                if (newChatBtn.isVisible()) {
                    newChatBtn.click();
                    fallback.resolveInput(page, selectors.getInputSelectorChain(), "DeepSeek 输入框(重置后)");
                    log.info("✅ DeepSeek 对话已重置");
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
                fallback.resolveInput(page, selectors.getInputSelectorChain(), "DeepSeek 输入框(fallback)");
                log.info("✅ DeepSeek 对话已重置（fallback）");
            }
        } catch (Exception e) {
            log.error("DeepSeek 对话重置失败: {}", e.getMessage());
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
                fallback.resolveInput(page, selectors.getInputSelectorChain(), "DeepSeek 健康检查输入框");
                inputVisible = true;
            } catch (Exception ignored) {
            }

            if (loggedIn && inputVisible) {
                log.info("✅ DeepSeek 健康检查通过");
                return HealthStatus.HEALTHY;
            } else if (loggedIn) {
                log.warn("⚠️ DeepSeek 已登录但输入框不可见");
                return HealthStatus.DEGRADED;
            } else {
                log.warn("⚠️ DeepSeek 未登录或页面异常");
                return HealthStatus.UNHEALTHY;
            }
        } catch (Exception e) {
            log.error("❌ DeepSeek 健康检查失败: {}", e.getMessage());
            return HealthStatus.UNHEALTHY;
        }
    }

    @Override
    public void close() {
        log.info("🔒 关闭 DeepSeek 适配器");
        initialized = false;
        page = null;
    }

    /** 获取当前对话区文本（仅 ds-markdown，排除侧边栏）。 */
    private String extractPageText() {
        try {
            return (String) page.evaluate(
                "() => { " +
                "  const mds = document.querySelectorAll('div.ds-markdown'); " +
                "  if (!mds.length) return ''; " +
                "  return Array.from(mds).map(el => el.innerText).join('\\n'); " +
                "}"
            );
        } catch (Exception e) {
            return "";
        }
    }

    /** 从前后文本差分中提取新增内容。 */
    private String diffText(String before, String after) {
        if (before == null || before.isBlank()) return after != null ? after : "";
        if (after == null || after.isBlank()) return "";

        // 找到 before 在 after 中的位置，返回之后的新增内容
        int idx = after.indexOf(before);
        if (idx >= 0) {
            return after.substring(idx + before.length()).trim();
        }

        // 如果完全匹配不到（页面结构变化大），尝试找公共前缀
        int commonLen = 0;
        int maxLen = Math.min(before.length(), after.length());
        while (commonLen < maxLen && before.charAt(commonLen) == after.charAt(commonLen)) {
            commonLen++;
        }
        if (commonLen > 100) {
            return after.substring(commonLen).trim();
        }

        // 兜底：返回 after
        return after.trim();
    }
}
