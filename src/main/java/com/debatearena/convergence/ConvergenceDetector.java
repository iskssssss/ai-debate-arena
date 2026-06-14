package com.debatearena.convergence;

import com.debatearena.model.ConvergenceResult;
import com.debatearena.model.ParticipantResponse;

import java.util.List;

/**
 * 收敛检测器接口 —— 判断三个 AI 的回答是否达成共识。
 * <p>
 * Phase 1（MVP）：Lucene TF-IDF + min(pairwise cosine)
 * Phase 2（增强）：Claim Extraction + Semantic Similarity + Agreement Classification
 */
public interface ConvergenceDetector {

    /**
     * 对三份 AI 回答执行收敛检测。
     *
     * @param responses 本轮三个平台的回答（2~3 份，允许单平台故障降级）
     * @return 收敛结果，包含是否收敛、相似度分数、共识观点等
     */
    ConvergenceResult check(List<ParticipantResponse> responses);
}
