package com.debatearena.config;

import com.debatearena.judge.ApiJudgeService;
import com.debatearena.judge.ChannelJudgeService;
import com.debatearena.judge.JudgeService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 整理服务 Bean 配置 —— 将 API 整理与通道整理两种实现注册为具名 Bean，
 * 并提供按 JudgeMode 路由的 Map 供 Orchestrator 注入。
 */
@Configuration
public class JudgeServiceConfig {

    @Bean
    public Map<String, JudgeService> judgeServiceMap(
            @Qualifier("apiJudgeService") JudgeService api,
            @Qualifier("channelJudgeService") JudgeService channel) {
        return Map.of(
                "apiJudgeService", api,
                "channelJudgeService", channel
        );
    }
}
