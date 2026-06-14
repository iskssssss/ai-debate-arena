package com.debatearena.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 裁判对单轮（或整场）辩论的整理记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeRoundRecord {

    /** 裁判分析正文（Markdown）。 */
    private String analysis;

    /** 生成时间。 */
    private LocalDateTime timestamp;

    /** 是否调用成功。 */
    private boolean success;

    /** 失败时的错误信息。 */
    private String errorMessage;

    /**
     * 构建成功记录。
     */
    public static JudgeRoundRecord success(String analysis) {
        return JudgeRoundRecord.builder()
                .analysis(analysis)
                .timestamp(LocalDateTime.now())
                .success(true)
                .build();
    }

    /**
     * 构建失败记录。
     */
    public static JudgeRoundRecord failure(String errorMessage) {
        return JudgeRoundRecord.builder()
                .errorMessage(errorMessage)
                .timestamp(LocalDateTime.now())
                .success(false)
                .build();
    }
}
