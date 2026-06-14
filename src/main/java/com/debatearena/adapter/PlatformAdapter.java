package com.debatearena.adapter;

import com.debatearena.browser.HealthStatus;
import com.debatearena.model.AiPlatform;
import com.microsoft.playwright.Page;

import java.util.concurrent.CompletableFuture;

/**
 * 平台适配器接口 —— 隔离各 AI 平台的 UI 差异。
 * <p>
 * 所有 Playwright DOM 操作严格限制在 Adapter 实现内，核心编排器不直接操作浏览器。
 * <p>
 * 生命周期：
 * <ol>
 *   <li>{@link #initialize(Page)} — 辩论开始时调用一次，绑定 Page</li>
 *   <li>{@link #sendPrompt(String)} — 每轮多次调用，发送 Prompt 并等待完整响应</li>
 *   <li>{@link #resetConversation()} — 每轮开始前调用，点击"New Chat"重置对话</li>
 *   <li>{@link #healthCheck()} — 启动时/辩论前调用，纯 DOM 检查</li>
 *   <li>{@link #close()} — 辩论结束时调用</li>
 * </ol>
 */
public interface PlatformAdapter {

    /**
     * 辩论开始时调用一次，绑定 Page 并导航到首页。
     */
    void initialize(Page page);

    /**
     * 发送 Prompt 并异步等待完整响应。
     *
     * @param prompt 完整 Prompt 文本
     * @return 包含 AI 响应的 CompletableFuture
     */
    CompletableFuture<String> sendPrompt(String prompt);

    /**
     * 点击"New Chat"按钮重置对话上下文。
     * 关键：不能依赖 new Page()，必须保持单 Page 跨轮存活。
     */
    void resetConversation();

    /**
     * 纯 DOM 健康检查（不发测试 Prompt）。
     * 验证：登录状态、输入框可见性、页面可访问性。
     *
     * @return 健康状态
     */
    HealthStatus healthCheck();

    /**
     * 关闭适配器，释放相关资源。
     */
    void close();

    /**
     * 所属 AI 平台。
     */
    AiPlatform getPlatform();
}
