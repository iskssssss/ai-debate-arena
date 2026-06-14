package com.debatearena.persistence;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.debatearena.model.ThirdPartyApiSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 三方 API 配置持久化 —— 保存至本机 {@code ~/.ai-debate-arena/api-config.json}。
 */
@Slf4j
@Component
public class ThirdPartyApiSettingsStore {

    private static final String CONFIG_PATH =
            System.getProperty("user.home") + "/.ai-debate-arena/api-config.json";

    /**
     * 读取已保存的 API 配置，不存在时返回 null。
     */
    public ThirdPartyApiSettings load() {
        Path file = Path.of(CONFIG_PATH);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return JSON.parseObject(json, ThirdPartyApiSettings.class);
        } catch (IOException e) {
            log.error("读取 API 配置失败: {}", e.getMessage());
            throw new RuntimeException("读取 API 配置失败", e);
        }
    }

    /**
     * 将 API 配置写入本机文件。
     */
    public void save(ThirdPartyApiSettings settings) {
        try {
            Path file = Path.of(CONFIG_PATH);
            Files.createDirectories(file.getParent());
            String json = JSON.toJSONString(settings, JSONWriter.Feature.PrettyFormat);
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("💾 三方 API 配置已保存 — {}", file);
        } catch (IOException e) {
            log.error("保存 API 配置失败: {}", e.getMessage());
            throw new RuntimeException("保存 API 配置失败", e);
        }
    }

    /**
     * 删除本机 API 配置文件。
     */
    public void delete() {
        try {
            Path file = Path.of(CONFIG_PATH);
            if (Files.deleteIfExists(file)) {
                log.info("🗑️ 三方 API 配置已清除");
            }
        } catch (IOException e) {
            log.error("清除 API 配置失败: {}", e.getMessage());
            throw new RuntimeException("清除 API 配置失败", e);
        }
    }
}
