package com.debatearena.adapter;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 多级选择器 fallback 机制。
 * <p>
 * 依次尝试选择器链中的每个选择器，找到第一个可见且可交互的元素。
 * 如果所有选择器都失败，抛出异常。
 */
@Slf4j
public class FallbackSelector {

    /**
     * 对输入框选择器链执行 fallback 解析。
     *
     * @param page              Playwright Page
     * @param selectorChain     选择器链（优先级从高到低）
     * @param elementDescription 元素描述（用于日志）
     * @return 匹配的 Locator
     */
    public Locator resolveInput(Page page, List<String> selectorChain, String elementDescription) {
        for (String selector : selectorChain) {
            if (selector == null || selector.isBlank()) continue;
            try {
                Locator locator = page.locator(selector).first();
                locator.waitFor(new Locator.WaitForOptions().setTimeout(5000));
                if (locator.isVisible()) {
                    log.debug("选择器匹配成功 [{}]: {}", elementDescription, selector);
                    return locator;
                }
            } catch (Exception e) {
                log.debug("选择器未匹配 [{}]: {} — 尝试下一个", elementDescription, selector);
            }
        }
        throw new RuntimeException("所有选择器均未匹配到 " + elementDescription
                + " — 链: " + String.join(" → ", selectorChain));
    }

    /**
     * 对按钮选择器链执行 fallback 解析。
     */
    public Locator resolveButton(Page page, List<String> selectorChain, String buttonDescription) {
        for (String selector : selectorChain) {
            if (selector == null || selector.isBlank()) continue;
            try {
                Locator locator = page.locator(selector).first();
                locator.waitFor(new Locator.WaitForOptions().setTimeout(5000));
                if (locator.isVisible() && locator.isEnabled()) {
                    log.debug("按钮匹配成功 [{}]: {}", buttonDescription, selector);
                    return locator;
                }
            } catch (Exception e) {
                log.debug("按钮未匹配 [{}]: {} — 尝试下一个", buttonDescription, selector);
            }
        }
        // 最后尝试：按 Enter 键（输入框已就绪的情况下）
        log.warn("所有按钮选择器均未匹配 [{}]，将使用 Enter 键作为兜底", buttonDescription);
        return null;
    }

    /**
     * 等待指定选择器出现（用于检测响应完成）。
     */
    public boolean waitForSelector(Page page, String selector, int timeoutMs) {
        if (selector == null || selector.isBlank()) return false;
        try {
            page.locator(selector).first().waitFor(
                    new Locator.WaitForOptions().setTimeout(timeoutMs));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查选择器是否在页面上可见。
     */
    public boolean isVisible(Page page, String selector, int timeoutMs) {
        if (selector == null || selector.isBlank()) return false;
        try {
            Locator locator = page.locator(selector).first();
            locator.waitFor(new Locator.WaitForOptions().setTimeout(timeoutMs));
            return locator.isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 等待指定选择器从页面上消失（用于检测 Stop Button 消失 = 生成完毕）。
     */
    public boolean waitForDisappear(Page page, String selector, int timeoutMs) {
        if (selector == null || selector.isBlank()) return false;
        try {
            // 先检查是否存在，不存在则直接返回
            if (page.locator(selector).count() == 0) return true;
            // 等待消失
            page.locator(selector).first().waitFor(
                    new Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN)
                            .setTimeout(timeoutMs));
            return true;
        } catch (Exception e) {
            log.debug("选择器未在 {}ms 内消失: {}", timeoutMs, selector);
            return false;
        }
    }
}
