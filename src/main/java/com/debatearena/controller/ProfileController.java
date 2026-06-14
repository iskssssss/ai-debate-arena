package com.debatearena.controller;

import com.debatearena.browser.*;
import com.debatearena.config.AiPlatformProperties;
import com.debatearena.controller.dto.ProfileStatusResponse;
import com.debatearena.controller.exception.LoginRequiredException;
import com.debatearena.model.AiPlatform;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Profile 管理 REST 控制器 —— 提供浏览器 Profile 登录状态查询、手动登录引导、健康检查等端点。
 */
@Slf4j
@RestController
@RequestMapping("/api/profiles")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileManager profileManager;
    private final PlaywrightManager playwrightManager;
    private final BrowserProcessCleaner browserProcessCleaner;
    private final AiPlatformProperties platformProperties;

    /**
     * 查询所有平台的 Profile 登录状态。
     *
     * <pre>
     * GET /api/profiles
     * </pre>
     */
    @GetMapping
    public ResponseEntity<Map<String, ProfileStatusResponse>> getAllProfiles() {
        log.debug("🔍 查询所有平台 Profile 状态");
        Map<String, ProfileStatusResponse> result = new LinkedHashMap<>();

        for (AiPlatform platform : AiPlatform.values()) {
            boolean ready = profileManager.isProfileReady(platform);
            LoginStatus loginStatus = profileManager.getEffectiveLoginStatus(platform);

            result.put(platform.name().toLowerCase(), ProfileStatusResponse.builder()
                    .platform(platform.name())
                    .loginStatus(loginStatus)
                    .profileReady(ready)
                    .message(profileManager.getStatusMessage(platform, loginStatus, ready))
                    .build());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 交互式手动登录 —— 打开可见浏览器窗口供用户登录指定平台。
     *
     * <pre>
     * POST /api/profiles/{platform}/setup
     * Body (可选): { "timeoutSeconds": 300 }
     * </pre>
     *
     * 浏览器窗口会自动打开，用户在窗口中完成登录后，后端检测到登录指示器
     * 即自动关闭浏览器，Profile 随即就绪。默认最长等待 5 分钟。
     */
    @PostMapping("/{platform}/setup")
    public ResponseEntity<ProfileStatusResponse> setupProfile(@PathVariable String platform,
                                                               @RequestBody(required = false) Map<String, Integer> body) {
        AiPlatform aiPlatform = parsePlatform(platform);
        int timeoutSeconds = (body != null && body.containsKey("timeoutSeconds"))
                ? body.get("timeoutSeconds") : 300; // 默认 5 分钟

        log.info("🖥️ 收到 {} 交互式登录请求 (超时={}s)", aiPlatform.name(), timeoutSeconds);

        // 加载选择器配置
        String url = getPlatformUrl(aiPlatform);
        SelectorProvider selectors = YamlSelectorProvider.load(platform.toLowerCase(), url);

        // 启动交互式登录（异步，因为会阻塞几分钟等待用户操作）
        boolean success = profileManager.runInteractiveSetup(aiPlatform, selectors, timeoutSeconds);

        if (success) {
            profileManager.updateLoginStatus(aiPlatform, LoginStatus.LOGGED_IN);
            return ResponseEntity.ok(ProfileStatusResponse.builder()
                    .platform(aiPlatform.name())
                    .loginStatus(LoginStatus.LOGGED_IN)
                    .profileReady(true)
                    .message("登录成功！Profile 已持久化到磁盘，后续可直接使用")
                    .build());
        } else {
            boolean ready = profileManager.isProfileReady(aiPlatform);
            LoginStatus status = LoginStatus.LOGIN_REQUIRED;
            profileManager.updateLoginStatus(aiPlatform, status);
            return ResponseEntity.ok(ProfileStatusResponse.builder()
                    .platform(aiPlatform.name())
                    .loginStatus(status)
                    .profileReady(ready)
                    .message(profileManager.getStatusMessage(aiPlatform, status, ready))
                    .build());
        }
    }

    /**
     * 删除指定平台的 Profile。
     *
     * <pre>
     * DELETE /api/profiles/{platform}
     * </pre>
     */
    @DeleteMapping("/{platform}")
    public ResponseEntity<Void> deleteProfile(@PathVariable String platform) {
        AiPlatform aiPlatform = parsePlatform(platform);
        log.info("🗑️ 删除 {} Profile", aiPlatform.name());
        playwrightManager.resetPlatform(aiPlatform);
        profileManager.deleteProfile(aiPlatform);
        return ResponseEntity.noContent().build();
    }

    /**
     * 指定平台的健康检查（纯 DOM，不发 Prompt）。
     *
     * <pre>
     * GET /api/profiles/{platform}/health
     * </pre>
     */
    @GetMapping("/{platform}/health")
    public ResponseEntity<Map<String, Object>> healthCheck(@PathVariable String platform) {
        AiPlatform aiPlatform = parsePlatform(platform);
        String url = getPlatformUrl(aiPlatform);
        SelectorProvider selectors = YamlSelectorProvider.load(platform.toLowerCase(), url);

        // 优先使用已有 Context（辩论运行时）
        var context = playwrightManager.getContext(aiPlatform);

        if (context != null) {
            LoginStatus loginStatus = profileManager.checkLoginStatus(context, aiPlatform, selectors);
            return buildHealthResponse(aiPlatform, loginStatus);
        }

        // 无活跃 Context 时：如果 Profile 已持久化，启动临时 Context 检测
        if (profileManager.isProfileReady(aiPlatform)) {
            log.info("🔍 {} Profile 已就绪，启动临时浏览器检测登录状态…", aiPlatform.name());
            try {
                var tempContext = playwrightManager.launchPersistentContextForSetup(
                        aiPlatform, profileManager.getProfilePath(aiPlatform));
                try {
                    LoginStatus loginStatus = profileManager.checkLoginStatus(
                            tempContext, aiPlatform, selectors);
                    // checkLoginStatus 内部已更新缓存
                    return buildHealthResponse(aiPlatform, loginStatus);
                } finally {
                    tempContext.close();
                }
            } catch (Exception e) {
                log.error("{} 临时健康检查失败: {}", aiPlatform.name(), e.getMessage());
                if (browserProcessCleaner.isProfileInUseError(e)) {
                    browserProcessCleaner.cleanupOrphanedBrowsers("health-retry-" + aiPlatform.name());
                    try {
                        var tempContext = playwrightManager.launchPersistentContextForSetup(
                                aiPlatform, profileManager.getProfilePath(aiPlatform));
                        try {
                            LoginStatus loginStatus = profileManager.checkLoginStatus(
                                    tempContext, aiPlatform, selectors);
                            return buildHealthResponse(aiPlatform, loginStatus);
                        } finally {
                            tempContext.close();
                        }
                    } catch (Exception retryEx) {
                        log.error("{} 健康检查重试仍失败: {}", aiPlatform.name(), retryEx.getMessage());
                    }
                }
                profileManager.updateLoginStatus(aiPlatform, LoginStatus.ERROR);
                return buildHealthResponse(aiPlatform, LoginStatus.ERROR);
            }
        }

        // 无 Profile 也无 Context
        profileManager.updateLoginStatus(aiPlatform, LoginStatus.LOGIN_REQUIRED);
        return ResponseEntity.ok(Map.of(
                "platform", aiPlatform.name(),
                "healthy", false,
                "loginStatus", "LOGIN_REQUIRED",
                "details", "Profile 未初始化，请点击「登录」按钮完成首次设置"
        ));
    }

    private ResponseEntity<Map<String, Object>> buildHealthResponse(AiPlatform platform, LoginStatus status) {
        return ResponseEntity.ok(Map.of(
                "platform", platform.name(),
                "healthy", status == LoginStatus.LOGGED_IN,
                "loginStatus", status.name(),
                "details", status == LoginStatus.LOGGED_IN
                        ? "平台健康，登录状态正常"
                        : "平台异常: " + status.name()
        ));
    }

    private AiPlatform parsePlatform(String name) {
        try {
            return AiPlatform.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知平台: " + name + "，有效值: gemini, chatgpt, deepseek");
        }
    }

    private String getPlatformUrl(AiPlatform platform) {
        var platforms = platformProperties.getPlatforms();
        var config = platforms.get(platform.name().toLowerCase());
        return config != null ? config.getUrl() : "";
    }
}
