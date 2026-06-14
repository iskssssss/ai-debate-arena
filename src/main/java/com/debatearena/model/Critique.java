package com.debatearena.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一个 AI 对另一个 AI 回答的批判意见。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Critique {

    /** 发出批判的 AI。 */
    private AiPlatform fromPlatform;

    /** 被批判的目标 AI。 */
    private AiPlatform targetPlatform;

    /** 批判者同意的观点。 */
    private String agreementPoints;

    /** 批判者反对的观点。 */
    private String disagreementPoints;

    /** 目标 AI 遗漏的重要内容。 */
    private String omissions;

    /** 整体质量评价：Strong / Adequate / Weak。 */
    private String qualityRating;
}
