package com.debatearena.controller.dto;

import com.debatearena.browser.LoginStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Profile 状态响应 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileStatusResponse {

    /** 平台名称。 */
    private String platform;

    /** 登录状态。 */
    private LoginStatus loginStatus;

    /** Profile 是否就绪。 */
    private boolean profileReady;

    /** 附加消息。 */
    private String message;
}
