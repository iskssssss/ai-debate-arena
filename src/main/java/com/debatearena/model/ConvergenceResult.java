package com.debatearena.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 收敛检测结果——在三份 AI 回答上执行相似度分析后的输出。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConvergenceResult {

    /** 是否检测到收敛（最小 pairwise 相似度 >= 阈值）。 */
    private boolean converged;

    /** 所有 pairwise 余弦相似度的平均值。 */
    private double averageSimilarity;

    /** 最小 pairwise 余弦相似度（最薄弱的环节）。 */
    private double minPairwiseSimilarity;

    /** 三个 AI 一致同意的关键观点（本地提取，不通过 AI prompt）。 */
    @Builder.Default
    private List<String> agreedPoints = new ArrayList<>();

    /** 逐对相似度分数，用于诊断展示。 */
    private Map<String, Double> pairwiseScores;
}
