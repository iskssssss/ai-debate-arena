package com.debatearena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Electron 桌面客户端配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "desktop")
public class DesktopConfig {

    /** 是否由 Electron 桌面客户端启动（用于区分 Web 与桌面运行环境）。 */
    private boolean enabled = false;
}
