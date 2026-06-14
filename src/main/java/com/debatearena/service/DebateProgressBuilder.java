package com.debatearena.service;

import com.debatearena.controller.dto.ProgressStepDto;
import com.debatearena.model.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 根据会话状态构建研讨进度步骤树，供前端时间线渲染。
 */
@Component
public class DebateProgressBuilder {

    /**
     * 构建完整进度步骤列表。
     */
    public List<ProgressStepDto> buildSteps(DebateSession session) {
        List<ProgressStepDto> steps = new ArrayList<>();
        steps.add(buildPrepStep(session));
        steps.addAll(buildRoundSteps(session));
        steps.add(buildConvergenceStep(session));
        if (session.isJudgeEnabled()) {
            steps.add(buildFinalizeStep(session));
        }
        if (hasOutputDocuments(session)) {
            steps.add(buildReportStep(session));
        }
        return steps;
    }

    /**
     * 判断本场是否配置了赛后产出文档。
     */
    private boolean hasOutputDocuments(DebateSession session) {
        return session.getOutputDocumentTypes() != null && !session.getOutputDocumentTypes().isEmpty();
    }

    /**
     * 当前阶段的中文描述。
     */
    public String buildCurrentPhase(DebateSession session) {
        if (session.getStatus() == DebateStatus.FAILED) {
            return "研讨异常终止";
        }
        if (isPostProcessing(session)) {
            return "正在整理汇总文档";
        }
        if (session.getStatus() == DebateStatus.CONVERGED) {
            return "研讨已完成";
        }
        if (session.getStatus() == DebateStatus.MAX_ROUNDS) {
            return "已达轮数上限";
        }
        return switch (session.getStatus()) {
            case CREATED, RUNNING -> "正在准备研讨环境";
            case INITIAL_ANSWER -> "第 1 轮：提交初始方案";
            case CRITIQUE -> "第 2 轮：交叉审阅";
            case REBUTTAL -> "第 " + (session.getCurrentRoundNumber() + 1) + " 轮：修订方案";
            case CONSENSUS -> "收敛确认";
            default -> "进行中";
        };
    }

    /**
     * 状态中文标签。
     */
    public String buildStatusLabel(DebateSession session) {
        if (isPostProcessing(session)) {
            return "整理中";
        }
        return switch (session.getStatus()) {
            case CONVERGED -> "已完成";
            case MAX_ROUNDS -> "已达上限";
            case FAILED -> "失败";
            case CREATED, RUNNING, INITIAL_ANSWER, CRITIQUE, REBUTTAL, CONSENSUS -> "进行中";
        };
    }

    /**
     * 是否处于赛后异步整理阶段。
     */
    public boolean isPostProcessing(DebateSession session) {
        boolean finished = session.getStatus() == DebateStatus.CONVERGED
                || session.getStatus() == DebateStatus.MAX_ROUNDS;
        if (!finished) {
            return false;
        }
        List<String> requested = session.getOutputDocumentTypes();
        if (requested == null || requested.isEmpty()) {
            return false;
        }
        Map<String, OutputDocumentRecord> generated = session.getGeneratedDocuments();
        if (generated == null || generated.isEmpty()) {
            return true;
        }
        for (String typeId : requested) {
            if (!generated.containsKey(typeId)) {
                return true;
            }
        }
        return false;
    }

    private ProgressStepDto buildPrepStep(DebateSession session) {
        String state = session.getCurrentRoundNumber() > 0 || isTerminal(session)
                ? "done" : isActiveStatus(session, DebateStatus.CREATED, DebateStatus.RUNNING, DebateStatus.INITIAL_ANSWER)
                && session.getCurrentRoundNumber() == 0 ? "active" : "pending";

        if (session.getStatus() == DebateStatus.FAILED && session.getCurrentRoundNumber() == 0) {
            state = "error";
        }

        List<ProgressStepDto> children = new ArrayList<>();
        for (AiPlatform platform : AiPlatform.values()) {
            if (!session.getParticipatingPlatforms().isEmpty()
                    && !session.getParticipatingPlatforms().contains(platform)
                    && session.getFailedPlatforms().contains(platform)) {
                continue;
            }
            String alias = session.getParticipantAliases().getOrDefault(platform, platform.name());
            boolean active = session.isPlatformActive(platform);
            String childState = active ? "done" : "error";
            if (!session.getParticipatingPlatforms().isEmpty() && !session.getParticipatingPlatforms().contains(platform)) {
                childState = "pending";
            }
            children.add(ProgressStepDto.builder()
                    .id("prep-" + platform.name())
                    .label(alias)
                    .detail(active ? "已接入" : "未参与")
                    .state(childState)
                    .build());
        }

        return ProgressStepDto.builder()
                .id("prep")
                .label("准备研讨环境")
                .detail("校验通道登录并启动浏览器")
                .state(state)
                .children(children)
                .build();
    }

    private List<ProgressStepDto> buildRoundSteps(DebateSession session) {
        List<ProgressStepDto> steps = new ArrayList<>();
        int max = session.getMaxRounds();
        for (int n = 1; n <= max; n++) {
            RoundType type = plannedRoundType(n);
            DebateRound completed = findRound(session, n);
            String state = resolveRoundState(session, n, completed);
            steps.add(ProgressStepDto.builder()
                    .id("round-" + n)
                    .label("第 " + n + " 轮 · " + roundTypeLabel(type))
                    .detail(roundTypeDetail(type))
                    .state(state)
                    .children(buildParticipantSteps(session, n, completed, state))
                    .build());
        }
        return steps;
    }

    private List<ProgressStepDto> buildParticipantSteps(DebateSession session, int roundNum,
                                                       DebateRound round, String roundState) {
        List<ProgressStepDto> children = new ArrayList<>();
        for (AiPlatform platform : session.getParticipatingPlatforms()) {
            if (platform == null) continue;
            String alias = session.getParticipantAlias(platform);
            String childState = "pending";
            String detail = "等待中";

            if (!session.isPlatformActive(platform)) {
                childState = "error";
                detail = "已退出";
            } else if (round != null) {
                ParticipantResponse resp = round.getResponse(platform);
                if (resp != null && resp.getContent() != null && !resp.getContent().isBlank()) {
                    childState = "done";
                    detail = "已提交（" + resp.getContent().length() + " 字）";
                } else if (round.getPrompts().containsKey(platform)) {
                    childState = "error";
                    detail = "未收到回复";
                }
            } else if ("error".equals(roundState)) {
                childState = session.isPlatformActive(platform) ? "done" : "error";
                detail = session.isPlatformActive(platform) ? "已提交" : "提交失败";
            } else if ("active".equals(roundState)) {
                childState = "active";
                detail = "撰写中…";
            }

            children.add(ProgressStepDto.builder()
                    .id("round-" + roundNum + "-" + platform.name())
                    .label(alias)
                    .detail(detail)
                    .state(childState)
                    .build());
        }
        return children;
    }

    private ProgressStepDto buildConvergenceStep(DebateSession session) {
        String state = "pending";
        String detail = "比对各方方案相似度";

        if (session.getStatus() == DebateStatus.FAILED) {
            state = "error";
        } else if (session.getStatus() == DebateStatus.CONVERGED) {
            state = "done";
            detail = "各方方案已趋同";
        } else if (session.getStatus() == DebateStatus.MAX_ROUNDS) {
            state = "done";
            detail = "达轮数上限，部分分歧保留";
        } else if (session.getCurrentRoundNumber() >= session.getMaxRounds()
                && isActiveStatus(session, DebateStatus.REBUTTAL)) {
            state = "active";
        }

        DebateRound last = session.getLatestRound();
        if (last != null && last.getConvergenceResult() != null) {
            ConvergenceResult c = last.getConvergenceResult();
            detail = String.format("相似度 %.0f%%（阈值 %.0f%%）",
                    c.getMinPairwiseSimilarity() * 100,
                    session.getConvergenceThreshold() * 100);
            if (c.isConverged()) {
                detail += "，已收敛";
            }
        }

        return ProgressStepDto.builder()
                .id("convergence")
                .label("收敛判定")
                .detail(detail)
                .state(state)
                .build();
    }

    private ProgressStepDto buildFinalizeStep(DebateSession session) {
        String state = "pending";
        String detail = "汇总各轮材料";

        if (session.getStatus() == DebateStatus.FAILED) {
            state = "pending";
        } else if (isPostProcessing(session)) {
            state = "active";
            detail = "正在生成产出文档…";
        } else if (session.getFinalJudgeRecord() != null && session.getFinalJudgeRecord().isSuccess()) {
            state = "done";
            detail = "整理完成";
        } else if (session.getFinalJudgeRecord() != null) {
            state = "error";
            detail = session.getFinalJudgeRecord().getErrorMessage() != null
                    ? session.getFinalJudgeRecord().getErrorMessage()
                    : "整理失败";
        } else if (isTerminal(session) && !session.isJudgeEnabled()) {
            state = "done";
        } else if (isTerminal(session)) {
            state = "active";
        }

        return ProgressStepDto.builder()
                .id("finalize")
                .label("汇总整理")
                .detail(detail)
                .state(state)
                .build();
    }

    private ProgressStepDto buildReportStep(DebateSession session) {
        String state = "pending";
        int total = session.getOutputDocumentTypes() != null ? session.getOutputDocumentTypes().size() : 0;
        String detail = "待生成 " + total + " 份文档";

        if (session.getStatus() == DebateStatus.CONVERGED || session.getStatus() == DebateStatus.MAX_ROUNDS) {
            if (isPostProcessing(session)) {
                state = "active";
                int done = session.getGeneratedDocuments() != null ? session.getGeneratedDocuments().size() : 0;
                detail = "正在生成产出文档（" + done + "/" + total + "）";
            } else {
                state = "done";
                detail = total + " 份文档可下载";
            }
        } else if (session.getStatus() == DebateStatus.FAILED) {
            state = "error";
            detail = "文档未生成";
        }

        return ProgressStepDto.builder()
                .id("report")
                .label("输出方案文档")
                .detail(detail)
                .state(state)
                .build();
    }

    private RoundType plannedRoundType(int roundNum) {
        if (roundNum == 1) return RoundType.INITIAL;
        if (roundNum == 2) return RoundType.CRITIQUE;
        return RoundType.REBUTTAL;
    }

    private String roundTypeLabel(RoundType type) {
        return switch (type) {
            case INITIAL -> "初始方案";
            case CRITIQUE -> "交叉审阅";
            case REBUTTAL -> "修订回应";
            case CONVERGENCE -> "收敛确认";
        };
    }

    private String roundTypeDetail(RoundType type) {
        return switch (type) {
            case INITIAL -> "各方独立提交实现方案";
            case CRITIQUE -> "审阅其他方方案并指出问题";
            case REBUTTAL -> "根据审阅意见修订方案";
            case CONVERGENCE -> "确认共识与分歧";
        };
    }

    private DebateRound findRound(DebateSession session, int roundNum) {
        return session.getRounds().stream()
                .filter(r -> r.getRoundNumber() == roundNum)
                .findFirst()
                .orElse(null);
    }

    private String resolveRoundState(DebateSession session, int roundNum, DebateRound completed) {
        if (completed != null && completed.getCompletedAt() != null) {
            return "done";
        }
        if (session.getStatus() == DebateStatus.FAILED) {
            int failedAt = session.getFailedAtRound();
            if (failedAt > 0) {
                if (roundNum == failedAt) return "error";
                if (roundNum < failedAt) return "done";
                return "pending";
            }
            int active = activeRoundNumber(session);
            if (roundNum == active && active > 0) return "error";
            if (roundNum < active) return "done";
            return "pending";
        }
        int active = activeRoundNumber(session);
        if (roundNum < active) return "done";
        if (roundNum == active) return "active";
        return "pending";
    }

    /**
     * 根据状态机推断当前进行中的轮次编号。
     */
    private int activeRoundNumber(DebateSession session) {
        if (session.getStatus() == DebateStatus.FAILED && session.getFailedAtRound() > 0) {
            return session.getFailedAtRound();
        }
        int completed = session.getCurrentRoundNumber();
        if (isTerminal(session)) {
            return completed;
        }
        return switch (session.getStatus()) {
            case CREATED, RUNNING -> 0;
            case INITIAL_ANSWER -> 1;
            case CRITIQUE -> 2;
            case REBUTTAL -> Math.min(completed + 1, session.getMaxRounds());
            default -> completed + 1;
        };
    }

    private boolean isTerminal(DebateSession session) {
        DebateStatus s = session.getStatus();
        return s == DebateStatus.CONVERGED || s == DebateStatus.MAX_ROUNDS || s == DebateStatus.FAILED;
    }

    private boolean isActiveStatus(DebateSession session, DebateStatus... statuses) {
        for (DebateStatus st : statuses) {
            if (session.getStatus() == st) return true;
        }
        return false;
    }
}
