package com.debatearena.persistence;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.debatearena.model.ChannelRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 通道注册表持久化 —— 保存至本机 {@code ~/.ai-debate-arena/channels.json}。
 */
@Slf4j
@Component
public class ChannelRegistryStore {

    private static final String REGISTRY_PATH =
            System.getProperty("user.home") + "/.ai-debate-arena/channels.json";

    /**
     * 读取通道注册表；文件不存在时返回 null。
     */
    public ChannelRegistry load() {
        Path file = Path.of(REGISTRY_PATH);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return JSON.parseObject(json, ChannelRegistry.class);
        } catch (IOException e) {
            log.error("读取通道注册表失败: {}", e.getMessage());
            throw new RuntimeException("读取通道注册表失败", e);
        }
    }

    /**
     * 保存通道注册表到本机。
     */
    public void save(ChannelRegistry registry) {
        try {
            Path file = Path.of(REGISTRY_PATH);
            Files.createDirectories(file.getParent());
            String json = JSON.toJSONString(registry, JSONWriter.Feature.PrettyFormat);
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("💾 通道注册表已保存 — {}", file);
        } catch (IOException e) {
            log.error("保存通道注册表失败: {}", e.getMessage());
            throw new RuntimeException("保存通道注册表失败", e);
        }
    }
}
