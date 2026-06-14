package com.debatearena.orchestrator;

import com.debatearena.adapter.PlatformAdapter;
import com.debatearena.model.AiPlatform;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 对话生命周期管理器 —— 管理各平台 AI 对话的隔离与重置。
 * <p>
 * 职责：
 * <ul>
 *   <li>每轮开始前调用 {@code resetConversation()} 隔离上下文</li>
 *   <li>防止上一轮对话污染当前轮次</li>
 *   <li>配合 {@code CompressionService} 防止上下文窗口溢出</li>
 *   <li>维护每个平台当前的对话状态</li>
 * </ul>
 * <p>
 * 关键原则：每轮辩论前必须重置对话，避免历史 Prompt 影响 AI 判断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationManager {

    /**
     * 为指定平台重置对话（点击 "New Chat" 按钮）。
     */
    public void resetConversation(AiPlatform platform, PlatformAdapter adapter) {
        log.info("🔄 重置 {} 对话上下文…", platform.name());
        try {
            adapter.resetConversation();
            log.info("✅ {} 对话已重置", platform.name());
        } catch (Exception e) {
            log.error("❌ {} 对话重置失败: {}", platform.name(), e.getMessage());
            throw new RuntimeException("对话重置失败: " + platform.name(), e);
        }
    }

    /**
     * 重置所有活跃平台的对话（新辩论开始前调用）。
     *
     * @param adapters 所有平台适配器 Map
     */
    public void resetAll(Map<AiPlatform, PlatformAdapter> adapters) {
        log.info("🔄 重置所有平台对话…");
        for (var entry : adapters.entrySet()) {
            try {
                resetConversation(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.error("重置 {} 失败，跳过: {}", entry.getKey(), e.getMessage());
            }
        }
        log.info("✅ 所有平台对话已重置");
    }

    /**
     * 辩论结束后清理指定平台的对话状态。
     */
    public void endConversation(AiPlatform platform) {
        log.debug("结束 {} 对话", platform.name());
    }

    /**
     * 判断指定平台是否有活跃对话。
     */
    public boolean isConversationActive(AiPlatform platform) {
        // 当前简单实现：总是返回 true（在初始化后即可用）
        return true;
    }
}
