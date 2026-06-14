package com.debatearena.browser;

import com.debatearena.config.BrowserConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 清理异常退出后残留的 Playwright/Chromium 浏览器进程与 Profile 锁文件。
 * <p>
 * 服务被强制终止时 {@link PlaywrightManager#destroy()} 可能来不及执行，
 * 导致后续健康检测启动浏览器失败（Profile 目录仍被占用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserProcessCleaner {

    private static final List<String> LOCK_FILE_NAMES = List.of(
            "SingletonLock", "SingletonSocket", "lockfile", "LOCK"
    );

    private final BrowserConfig browserConfig;

    /**
     * 应用就绪后清理上次异常退出残留的浏览器进程。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {
        cleanupOrphanedBrowsers("应用启动");
    }

    /**
     * 终止命令行中包含本应用 Profile 路径的残留浏览器进程，并清除锁文件。
     *
     * @param reason 触发原因（用于日志）
     * @return 终止的进程数量
     */
    public int cleanupOrphanedBrowsers(String reason) {
        Path base = resolveProfileBasePath();
        String marker = normalizePath(base);
        log.info("🧹 清理残留浏览器（{}）— profileRoot={}", reason, base);

        int killed = killProcessesByCommandLine(marker);
        if (killed > 0) {
            log.info("🧹 已终止 {} 个残留浏览器进程", killed);
            sleepBriefly(800);
        }

        int locks = clearProfileLockFiles(base);
        if (locks > 0) {
            log.info("🧹 已清除 {} 个 Profile 锁文件", locks);
        }
        return killed;
    }

    /**
     * 判断异常是否由 Profile 目录被占用引起。
     */
    public boolean isProfileInUseError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("user data directory")
                        || lower.contains("already in use")
                        || lower.contains("singletonlock")
                        || lower.contains("process_singleton")
                        || lower.contains("failed to create data directory")
                        || lower.contains("profile directory")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 扫描并终止命令行匹配本应用 Profile 的浏览器进程。
     */
    private int killProcessesByCommandLine(String pathMarker) {
        int count = 0;
        try {
            long currentPid = ProcessHandle.current().pid();
            for (ProcessHandle handle : ProcessHandle.allProcesses().toList()) {
                if (handle.pid() == currentPid) {
                    continue;
                }
                Optional<String> cmd = handle.info().commandLine();
                if (cmd.isEmpty() || !matchesOurBrowser(cmd.get(), pathMarker)) {
                    continue;
                }
                log.debug("终止残留浏览器 pid={} — {}", handle.pid(), truncate(cmd.get(), 180));
                handle.destroyForcibly();
                count++;
            }
        } catch (Exception e) {
            log.warn("扫描残留浏览器进程失败: {}", e.getMessage());
        }
        return count;
    }

    /**
     * 判断进程命令行是否属于本应用的 Playwright 浏览器实例。
     */
    private boolean matchesOurBrowser(String commandLine, String pathMarker) {
        String normalized = commandLine.replace('\\', '/').toLowerCase();
        String marker = pathMarker.toLowerCase();
        if (!normalized.contains(marker)) {
            return false;
        }
        return normalized.contains("chrome")
                || normalized.contains("chromium")
                || normalized.contains("ms-playwright");
    }

    /**
     * 递归清除各 Profile 目录下的 Chromium 单例锁文件。
     */
    private int clearProfileLockFiles(Path profileRoot) {
        if (!Files.isDirectory(profileRoot)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> walk = Files.walk(profileRoot, 4)) {
            for (Path path : walk.toList()) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (LOCK_FILE_NAMES.contains(name)) {
                    if (Files.deleteIfExists(path)) {
                        removed++;
                        log.debug("已删除锁文件: {}", path);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("清除 Profile 锁文件失败: {}", e.getMessage());
        }
        return removed;
    }

    /** 展开 profile 根目录配置。 */
    private Path resolveProfileBasePath() {
        String base = browserConfig.getProfileBaseDir();
        if (base.contains("${user.home}")) {
            base = base.replace("${user.home}", System.getProperty("user.home"));
        }
        return Paths.get(base);
    }

    /** 统一路径分隔符，便于命令行匹配。 */
    private String normalizePath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "…";
    }

    private void sleepBriefly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
