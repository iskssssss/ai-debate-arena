package com.debatearena.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 辩论完成后的最终结果 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateResult {

    /** 会话标识。 */
    private String sessionId;

    /** 最终辩论状态。 */
    private DebateStatus status;

    /** 实际执行的总轮数。 */
    private int totalRounds;

    /** 最终的 Markdown 报告内容。 */
    private String markdownReport;

    /** 辩论完成时间。 */
    private LocalDateTime completedAt;
}
