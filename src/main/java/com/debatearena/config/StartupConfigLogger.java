package com.debatearena.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 应用启动后打印关键配置摘要，便于调试时快速确认运行参数。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupConfigLogger implements ApplicationRunner {

    private final DebateConfig debateConfig;
    private final BrowserConfig browserConfig;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        log.info("═══════════════════════════════════════════════════════");
        log.info("  AI Debate Arena 启动配置摘要");
        log.info("═══════════════════════════════════════════════════════");
        log.info("  Active Profiles : {}", Arrays.toString(environment.getActiveProfiles()));
        log.info("  Server Port       : {}", environment.getProperty("server.port", "8080"));
        log.info("  ── 研讨配置 ──");
        log.info("  maxRounds         : {}", debateConfig.getMaxRounds());
        log.info("  convergenceThreshold: {}", debateConfig.getConvergenceThreshold());
        log.info("  aiTimeoutSeconds  : {}", debateConfig.getAiTimeoutSeconds());
        log.info("  parallelExecution : {}", debateConfig.isParallelExecution());
        log.info("  ── 浏览器配置 ──");
        log.info("  headless          : {}", browserConfig.isHeadless());
        log.info("  slowMo            : {}ms", browserConfig.getSlowMo());
        log.info("  channel           : {}", browserConfig.getChannel());
        log.info("  profileBaseDir    : {}", browserConfig.getProfileBaseDir());
        log.info("  viewport          : {}x{}", browserConfig.getViewportWidth(), browserConfig.getViewportHeight());
        log.info("═══════════════════════════════════════════════════════");
        log.debug("调试模式已启用 — 日志级别 DEBUG，详细日志将输出到控制台和 logs/ 目录");
    }
}
