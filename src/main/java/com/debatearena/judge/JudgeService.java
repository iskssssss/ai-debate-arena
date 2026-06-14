package com.debatearena.judge;

import com.debatearena.model.DebateRound;
import com.debatearena.model.DebateSession;
import com.debatearena.model.JudgeRoundRecord;

/**
 * 研讨整理服务接口 —— 支持 API 整理与通道整理两种实现。
 */
public interface JudgeService {

    /**
     * 对单轮研讨材料进行整理总结。
     */
    JudgeRoundRecord summarizeRound(DebateSession session, DebateRound round);

    /**
     * 对所有轮次材料进行最终整理总结。
     */
    JudgeRoundRecord summarizeFinal(DebateSession session);

    /**
     * 根据系统提示词与用户提示词生成文档正文。
     */
    String generateDocumentContent(DebateSession session, String systemPrompt, String userPrompt);

    /**
     * 本场研讨是否启用了整理服务。
     */
    boolean isJudgeEnabled(DebateSession session);

    /**
     * 注册会话（API 模式：存储 API Key；通道模式：初始化浏览器页面）。
     */
    void registerSession(String sessionId, DebateSession session);

    /**
     * 清理会话相关资源。
     */
    void cleanup(String sessionId);
}
