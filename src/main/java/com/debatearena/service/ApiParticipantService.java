package com.debatearena.service;

import com.debatearena.judge.DeepSeekApiClient;
import com.debatearena.model.ChannelDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * API 通道研讨参与方：通过 OpenAI 兼容接口收发 Prompt。
 */
@Service
@RequiredArgsConstructor
public class ApiParticipantService {

    private static final String DEBATE_SYSTEM_PROMPT =
            "你是方案研讨讨论方。请根据用户给出的研讨任务与格式要求，输出完整、可落地的技术方案内容。"
                    + "使用 Markdown 组织回答，不要编造未给出的外部系统细节。";

    private final DeepSeekApiClient apiClient;

    /**
     * 向 API 通道发送研讨 Prompt 并返回模型回复。
     */
    public String chat(ChannelDefinition channel, String userPrompt) {
        if (channel.getApiKey() == null || channel.getApiKey().isBlank()) {
            throw new IllegalStateException(channel.getDisplayName() + " 未配置 API Key");
        }
        return apiClient.chat(
                channel.getBaseUrl(),
                channel.getApiKey(),
                channel.getModel(),
                DEBATE_SYSTEM_PROMPT,
                userPrompt
        );
    }
}
