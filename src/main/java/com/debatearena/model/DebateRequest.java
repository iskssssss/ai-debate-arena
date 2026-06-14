package com.debatearena.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动新辩论的请求 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateRequest {

    /** 辩论主题（必填）。 */
    @NotBlank(message = "辩论主题不能为空")
    private String topic;

    /** 最大轮数（2~10）。 */
    @Min(value = 2, message = "至少需要 2 轮辩论")
    @Max(value = 10, message = "最多允许 10 轮辩论")
    @Builder.Default
    private int maxRounds = 5;

    /** 收敛阈值（0.5~1.0）。 */
    @DecimalMin(value = "0.5", message = "收敛阈值最低为 0.5")
    @DecimalMax(value = "1.0", message = "收敛阈值最高为 1.0")
    @Builder.Default
    private double convergenceThreshold = 0.75;

    /** 是否启用裁判（研讨默认必须启用，需同时提供 judgeApiKey 或 judgeChannel）。 */
    @Builder.Default
    private boolean judgeEnabled = true;

    /** 整理方式：API（DeepSeek HTTP）或 CHANNEL（浏览器自动化通道）。 */
    @Builder.Default
    private JudgeMode judgeMode = JudgeMode.API;

    /** DeepSeek API Key（API 整理模式必填）。 */
    private String judgeApiKey;

    /** 裁判模型，默认 deepseek-v4-flash。 */
    @Builder.Default
    private String judgeModel = "deepseek-v4-flash";

    /** 通道整理模式下的整理方平台。 */
    private AiPlatform judgeChannel;

    /** 赛后需产出的文档类型 ID 列表（为空时使用系统默认）。 */
    @Builder.Default
    private List<String> outputDocuments = new ArrayList<>();
}
