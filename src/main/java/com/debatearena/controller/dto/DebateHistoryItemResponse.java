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
 * 研讨历史列表项 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateHistoryItemResponse {

    private String sessionId;
    private String topic;
    private DebateStatus status;
    private String statusLabel;
    private int currentRound;
    private int maxRounds;

    /** 参与讨论方代号列表。 */
    @Builder.Default
    private List<String> participants = new ArrayList<>();

    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
