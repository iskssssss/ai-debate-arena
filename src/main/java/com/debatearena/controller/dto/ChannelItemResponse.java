package com.debatearena.controller.dto;

import com.debatearena.browser.LoginStatus;
import com.debatearena.model.ChannelType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通道列表项响应（含浏览器登录态或 API 配置状态）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelItemResponse {

    private String id;
    private boolean builtin;
    private ChannelType type;
    private String displayName;
    private String platform;
    private boolean enabled;

    /** 浏览器通道：登录状态。 */
    private LoginStatus loginStatus;
    private boolean profileReady;

    /** API 通道：是否已配置 Key。 */
    private boolean apiKeyConfigured;
    private String apiKeyMasked;
    private String baseUrl;
    private String model;

    /** API 通道：是否已通过连通性检测。 */
    private boolean apiVerified;

    /** 是否满足参与研讨条件。 */
    private boolean ready;

    private String message;
}
