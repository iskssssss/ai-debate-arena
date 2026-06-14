package com.debatearena.persistence;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.debatearena.model.DebateSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 辩论状态持久化 —— 每轮结束将 DebateSession 序列化为 JSON 写入磁盘。
 * <p>
 * 存储结构：
 * <pre>
 * ~/.ai-debate-arena/sessions/{sessionId}/
 *   ├── round-1.json
 *   ├── round-2.json
 *   └── round-3.json
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DebateStateStore {

    /** 会话持久化根目录。 */
    private static final String SESSIONS_BASE = System.getProperty("user.home") + "/.ai-debate-arena/sessions";

    /**
     * 保存当前轮次的完整会话快照。
     *
     * @param sessionId  会话 ID
     * @param roundNumber 轮次编号
     * @param session    会话完整状态
     */
    public void saveSnapshot(String sessionId, int roundNumber, DebateSession session) {
        try {
            Path dir = getSessionDir(sessionId);
            Files.createDirectories(dir);

            Path file = dir.resolve("round-" + roundNumber + ".json");
            String json = JSON.toJSONString(session, JSONWriter.Feature.PrettyFormat,
                    JSONWriter.Feature.WriteMapNullValue);
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            log.info("💾 快照已保存 — session={}, round={}, file={}", sessionId, roundNumber, file);
        } catch (IOException e) {
            log.error("保存快照失败 — session={}, round={}: {}", sessionId, roundNumber, e.getMessage());
            throw new RuntimeException("状态持久化失败", e);
        }
    }

    /**
     * 加载指定轮次的会话快照。
     *
     * @param sessionId  会话 ID
     * @param roundNumber 轮次编号
     * @return 反序列化的 DebateSession，若不存在则返回 null
     */
    public DebateSession loadSnapshot(String sessionId, int roundNumber) {
        Path file = getSessionDir(sessionId).resolve("round-" + roundNumber + ".json");
        if (!Files.exists(file)) {
            log.warn("快照文件不存在 — {}", file);
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            DebateSession session = JSON.parseObject(json, DebateSession.class);
            log.info("📂 快照已加载 — session={}, round={}", sessionId, roundNumber);
            return session;
        } catch (IOException e) {
            log.error("加载快照失败 — {}: {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * 列出指定会话所有已保存的轮次编号（升序）。
     */
    public List<Integer> listSavedRounds(String sessionId) {
        List<Integer> rounds = new ArrayList<>();
        Path dir = getSessionDir(sessionId);
        if (!Files.exists(dir)) return rounds;

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().matches("round-\\d+\\.json"))
                    .forEach(f -> {
                        String name = f.getFileName().toString();
                        int num = Integer.parseInt(name.replaceAll("\\D+", ""));
                        rounds.add(num);
                    });
        } catch (IOException e) {
            log.error("列出快照失败 — session={}: {}", sessionId, e.getMessage());
        }
        rounds.sort(Comparator.naturalOrder());
        return rounds;
    }

    /**
     * 获取最近一次保存的快照（最大轮次编号）。
     */
    public DebateSession loadLatestSnapshot(String sessionId) {
        List<Integer> rounds = listSavedRounds(sessionId);
        if (rounds.isEmpty()) return null;
        int latest = rounds.get(rounds.size() - 1);
        return loadSnapshot(sessionId, latest);
    }

    /**
     * 读取最近保存的会话快照，用于构建研讨历史列表。
     *
     * @param limit 最大返回数量
     * @return 按更新时间倒序排列的会话列表
     */
    public List<DebateSession> listLatestSnapshots(int limit) {
        Path baseDir = Paths.get(SESSIONS_BASE);
        if (!Files.exists(baseDir)) {
            return List.of();
        }

        List<DebateSession> sessions = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(baseDir)) {
            dirs.filter(Files::isDirectory)
                    .map(path -> loadLatestSnapshot(path.getFileName().toString()))
                    .filter(session -> session != null)
                    .forEach(sessions::add);
        } catch (IOException e) {
            log.error("读取研讨历史失败: {}", e.getMessage());
        }

        return sessions.stream()
                .sorted(Comparator.comparing(DebateSession::getUpdatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    /**
     * 删除指定会话的所有快照文件及目录。
     */
    public void deleteSession(String sessionId) {
        Path dir = getSessionDir(sessionId);
        if (!Files.exists(dir)) return;
        try {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder())
                        .forEach(f -> {
                            try {
                                Files.deleteIfExists(f);
                            } catch (IOException e) {
                                log.warn("删除文件失败: {}", f);
                            }
                        });
            }
            log.info("🗑️ 会话数据已删除 — session={}", sessionId);
        } catch (IOException e) {
            log.error("删除会话失败 — session={}: {}", sessionId, e.getMessage());
        }
    }

    private Path getSessionDir(String sessionId) {
        return Paths.get(SESSIONS_BASE, sessionId);
    }
}
