package com.debatearena.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步执行配置——为并行 AI 交互和后台辩论执行提供线程池。
 */
@Slf4j
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class AsyncConfig {

    private final DebateConfig debateConfig;

    /**
     * 辩论专用线程池——驱动三平台并行交互及后台辩论编排。
     */
    @Bean(name = "debateExecutor")
    public Executor debateExecutor() {
        DebateConfig.Executor cfg = debateConfig.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(cfg.getCorePoolSize());
        executor.setMaxPoolSize(cfg.getMaxPoolSize());
        executor.setQueueCapacity(cfg.getQueueCapacity());
        executor.setThreadNamePrefix("debate-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler((r, e) ->
                log.error("辩论任务被拒绝——队列已满，请考虑增大 executor.queue-capacity。"));
        executor.initialize();
        return executor;
    }

    /**
     * 工具线程池——用于健康检查、Profile 操作等轻量后台任务。
     */
    @Bean(name = "utilityExecutor")
    public Executor utilityExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("util-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
