package com.debatearena.model;

/**
 * 辩论会话的状态机阶段。
 */
public enum DebateStatus {
    /** 已创建，尚未开始。 */
    CREATED,
    /** 辩论正在运行。 */
    RUNNING,
    /** 第 1 轮：生成初始回答。 */
    INITIAL_ANSWER,
    /** 第 2 轮：AI 之间交叉批判。 */
    CRITIQUE,
    /** 第 3 轮+：反驳循环。 */
    REBUTTAL,
    /** 收敛确认阶段。 */
    CONSENSUS,
    /** 辩论结束——达成共识。 */
    CONVERGED,
    /** 辩论结束——达到最大轮数未收敛。 */
    MAX_ROUNDS,
    /** 辩论失败（平台不足、崩溃等）。 */
    FAILED
}
