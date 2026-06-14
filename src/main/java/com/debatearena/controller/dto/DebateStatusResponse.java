package com.debatearena.controller.dto;

import com.debatearena.model.DebateStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 研讨状态查询响应 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateStatusResponse {

    private String sessionId;
    private String topic;
    private DebateStatus status;

    /** 状态中文标签（已完成 / 进行中 / 整理中）。 */
    private String statusLabel;

    /** 当前阶段描述。 */
    private String currentPhase;

    private int currentRound;
    private int maxRounds;
    private int activePlatforms;

    /** 本场配置的收敛阈值（0.5~1.0）。 */
    private double convergenceThreshold;

    /** 参与讨论方代号列表。 */
    @Builder.Default
    private List<String> participants = new ArrayList<>();

    private boolean judgeEnabled;

    /** 是否处于赛后异步整理。 */
    private boolean postProcessing;

    /** 失败原因（FAILED 状态时展示）。 */
    private String failureReason;

    /** 进度步骤树。 */
    @Builder.Default
    private List<ProgressStepDto> steps = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
