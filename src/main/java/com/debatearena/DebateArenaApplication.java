package com.debatearena;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * AI 需求方案研讨 —— 启动类。
 * <p>
 * 输入需求后，驱动多个 AI 进行多轮实现方案研讨，
 * 最终生成供开发团队查阅的 Markdown 实现方案文档。
 */
@Slf4j
@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties
public class DebateArenaApplication {

    public static void main(String[] args) {
        SpringApplication.run(DebateArenaApplication.class, args);
        log.info("🚀 AI 需求方案研讨已启动");
    }
}
