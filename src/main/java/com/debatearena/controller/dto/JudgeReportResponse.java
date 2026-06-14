package com.debatearena.controller.dto;

import com.debatearena.model.JudgeRoundRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 裁判报告 API 响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeReportResponse {

    private String sessionId;
    private String topic;
    private boolean judgeEnabled;
    private String judgeModel;
    private List<RoundJudgeDetail> rounds;
    private JudgeRoundRecord finalJudge;

    /**
     * 单轮裁判详情（含各辩方 prompt 与 response）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoundJudgeDetail {
        private int roundNumber;
        private String roundType;
        private Map<String, String> prompts;
        private Map<String, String> responses;
        private JudgeRoundRecord judgeRecord;
    }
}
