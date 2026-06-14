package com.debatearena.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 某个 AI 参与者在单轮辩论中的一次回答。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParticipantResponse {

    /** 产生此回答的 AI 平台。 */
    private AiPlatform platform;

    /** 回答的完整文本内容。 */
    private String content;

    /** 回答接收时间。 */
    private LocalDateTime timestamp;

    /** 响应耗时（毫秒），从发送 Prompt 到完整接收。 */
    private long responseTimeMs;

    public static ParticipantResponse of(AiPlatform platform, String content, long responseTimeMs) {
        return ParticipantResponse.builder()
                .platform(platform)
                .content(content)
                .timestamp(LocalDateTime.now())
                .responseTimeMs(responseTimeMs)
                .build();
    }
}
