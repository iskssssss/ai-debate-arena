package com.debatearena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 辩论核心配置，绑定到 application.yml 中的 {@code debate.*} 配置段。
 */
@Data
@Component
@ConfigurationProperties(prefix = "debate")
public class DebateConfig {

    /** 最大辩论轮数，达到后强制终止（默认 5）。 */
    private int maxRounds = 5;

    /** 收敛判定的相似度阈值（0.0 ~ 1.0）。 */
    private double convergenceThreshold = 0.75;

    /** 单次 AI 交互超时（秒）。 */
    private int aiTimeoutSeconds = 120;

    /** 是否在一轮内并行执行三个 AI（true = 并行）。 */
    private boolean parallelExecution = true;

    /** 线程池配置。 */
    private Executor executor = new Executor();

    @Data
    public static class Executor {
        /** 核心线程数。 */
        private int corePoolSize = 4;
        /** 最大线程数。 */
        private int maxPoolSize = 8;
        /** 队列容量。 */
        private int queueCapacity = 100;
    }
}
