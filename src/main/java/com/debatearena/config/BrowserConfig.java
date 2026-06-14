package com.debatearena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 浏览器自动化配置，绑定到 application.yml 中的 {@code browser.*} 配置段。
 */
@Data
@Component
@ConfigurationProperties(prefix = "browser")
public class BrowserConfig {

    /** 持久化浏览器 Profile 的根目录。 */
    private String profileBaseDir = "${user.home}/.ai-debate-arena/profiles";

    /** 浏览器渠道：{@code chromium}（Playwright 内置）或 {@code chrome}（系统安装）。 */
    private String channel = "chromium";

    /** 无头模式（生产环境 true，调试时 false）。 */
    private boolean headless = true;

    /** 操作间慢放延迟（毫秒），0 = 禁用，调试时可设 300-500。 */
    private int slowMo = 0;

    /** 视口宽度（像素）。 */
    private int viewportWidth = 1280;

    /** 视口高度（像素）。 */
    private int viewportHeight = 900;
}
