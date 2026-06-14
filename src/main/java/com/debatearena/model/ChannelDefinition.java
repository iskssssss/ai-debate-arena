package com.debatearena.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 研讨通道定义：内置浏览器通道可切换为 API；支持新增自定义 API 通道。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelDefinition {

    /** 通道唯一 ID（内置：chatgpt/deepseek/gemini；自定义：api-{uuid}）。 */
    private String id;

    /** 是否为系统内置通道（内置通道不可删除）。 */
    @Builder.Default
    private boolean builtin = false;

    /** 接入方式。 */
    @Builder.Default
    private ChannelType type = ChannelType.BROWSER;

    /** 展示名称（可在通道配置页修改）。 */
    private String displayName;

    /** 关联的平台枚举名（内置通道：CHATGPT/DEEPSEEK/GEMINI）。 */
    private String platform;

    /** API Base URL（API 通道必填）。 */
    private String baseUrl;

    /** API Key（API 通道必填，仅本地存储）。 */
    private String apiKey;

    /** 模型名称（API 通道必填）。 */
    private String model;

    /** 是否启用该通道。 */
    @Builder.Default
    private boolean enabled = true;

    /** API 通道是否已通过连通性检测（检测成功后方可参与研讨）。 */
    @Builder.Default
    private boolean apiVerified = false;
}
