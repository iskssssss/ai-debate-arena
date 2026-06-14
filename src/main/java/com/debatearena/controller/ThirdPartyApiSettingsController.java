package com.debatearena.controller;

import com.debatearena.controller.dto.ThirdPartyApiSettingsRequest;
import com.debatearena.controller.dto.ThirdPartyApiSettingsResponse;
import com.debatearena.controller.dto.ThirdPartyApiTestResponse;
import com.debatearena.service.ThirdPartyApiSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 三方 API 整理服务配置 REST 控制器（通道配置页使用）。
 */
@Slf4j
@RestController
@RequestMapping("/api/profiles/api-config")
@RequiredArgsConstructor
public class ThirdPartyApiSettingsController {

    private final ThirdPartyApiSettingsService settingsService;

    /**
     * 查询当前三方 API 配置（Key 脱敏）。
     *
     * <pre>GET /api/profiles/api-config</pre>
     */
    @GetMapping
    public ResponseEntity<ThirdPartyApiSettingsResponse> getSettings() {
        return ResponseEntity.ok(settingsService.getDisplaySettings());
    }

    /**
     * 保存三方 API 配置。
     *
     * <pre>PUT /api/profiles/api-config</pre>
     */
    @PutMapping
    public ResponseEntity<ThirdPartyApiSettingsResponse> saveSettings(
            @RequestBody ThirdPartyApiSettingsRequest request) {
        log.info("💾 保存三方 API 配置 — baseUrl={}, model={}", request.getBaseUrl(), request.getModel());
        return ResponseEntity.ok(settingsService.save(request));
    }

    /**
     * 检测三方 API 连通性。
     *
     * <pre>POST /api/profiles/api-config/test</pre>
     */
    @PostMapping("/test")
    public ResponseEntity<ThirdPartyApiTestResponse> testSettings(
            @RequestBody(required = false) ThirdPartyApiSettingsRequest request) {
        log.info("🔍 检测三方 API 连通性");
        return ResponseEntity.ok(settingsService.test(request));
    }

    /**
     * 清除本机保存的三方 API 配置。
     *
     * <pre>DELETE /api/profiles/api-config</pre>
     */
    @DeleteMapping
    public ResponseEntity<Void> clearSettings() {
        log.info("🗑️ 清除三方 API 配置");
        settingsService.clear();
        return ResponseEntity.noContent().build();
    }
}
