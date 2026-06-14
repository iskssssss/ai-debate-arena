package com.debatearena.browser;

import java.util.List;
import java.util.Map;

/**
 * 选择器提供者接口 —— 每个平台的 YAML 选择器配置的加载与查询。
 * <p>
 * 为每个平台提供多级 CSS/XPath 选择器链，支持 fallback。
 */
public interface SelectorProvider {

    /** 获取平台的输入框选择器（主）。 */
    String getInputSelector();

    /** 获取平台的输入框选择器（fallback）。 */
    String getInputFallbackSelector();

    /** 获取发送按钮选择器（主）。 */
    String getSubmitSelector();

    /** 获取发送按钮选择器（fallback）。 */
    String getSubmitFallbackSelector();

    /** 获取最新 AI 回答的选择器。 */
    String getLatestResponseSelector();

    /** 获取登录指示器选择器（主）。 */
    String getLoginIndicator();

    /** 获取登录指示器选择器（fallback）。 */
    String getLoginIndicatorFallback();

    /** 获取响应完成指示器选择器（如复制按钮出现 = 生成完毕）。 */
    String getResponseWaitIndicator();

    /** 获取「复制回答」按钮选择器（主）。 */
    String getCopyResponseSelector();

    /** 获取「复制回答」按钮选择器（fallback）。 */
    String getCopyResponseFallbackSelector();

    /** 获取停止生成按钮选择器（主）。 */
    String getStopGeneratingSelector();

    /** 获取停止生成按钮选择器（fallback）。 */
    String getStopGeneratingFallback();

    /** 获取"新建对话"按钮选择器（主）。 */
    String getNewChatSelector();

    /** 获取"新建对话"按钮选择器（fallback）。 */
    String getNewChatFallbackSelector();

    /** 获取平台首页 URL。 */
    String getPlatformUrl();

    /** 获取超时配置（毫秒）。 */
    Map<String, Integer> getTimeouts();

    /** 获取包含所有备选选择器的 fallback 链。 */
    List<String> getInputSelectorChain();

    /** 获取提交按钮选择器的 fallback 链。 */
    List<String> getSubmitSelectorChain();

    /** 获取复制回答按钮选择器的 fallback 链。 */
    List<String> getCopyResponseSelectorChain();
}
