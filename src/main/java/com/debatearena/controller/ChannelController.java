package com.debatearena.controller;

import com.debatearena.controller.dto.ChannelCreateRequest;
import com.debatearena.controller.dto.ChannelItemResponse;
import com.debatearena.controller.dto.ChannelUpdateRequest;
import com.debatearena.controller.dto.ThirdPartyApiTestResponse;
import com.debatearena.service.ChannelRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通道注册表 REST 控制器（通道配置页：重命名、API 模式、新增 API 通道）。
 */
@Slf4j
@RestController
@RequestMapping("/api/profiles/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelRegistryService channelRegistryService;

    /**
     * 列出全部通道及状态。
     *
     * <pre>GET /api/profiles/channels</pre>
     */
    @GetMapping
    public ResponseEntity<Map<String, ChannelItemResponse>> listChannels() {
        List<ChannelItemResponse> items = channelRegistryService.listChannelsWithStatus();
        Map<String, ChannelItemResponse> result = new LinkedHashMap<>();
        for (ChannelItemResponse item : items) {
            result.put(item.getId(), item);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 更新指定通道配置。
     *
     * <pre>PUT /api/profiles/channels/{channelId}</pre>
     */
    @PutMapping("/{channelId}")
    public ResponseEntity<ChannelItemResponse> updateChannel(
            @PathVariable String channelId,
            @RequestBody ChannelUpdateRequest request) {
        log.info("💾 更新通道 {} — type={}", channelId, request.getType());
        return ResponseEntity.ok(channelRegistryService.updateChannel(channelId, request));
    }

    /**
     * 新增自定义 API 通道。
     *
     * <pre>POST /api/profiles/channels</pre>
     */
    @PostMapping
    public ResponseEntity<ChannelItemResponse> createChannel(@RequestBody ChannelCreateRequest request) {
        log.info("➕ 新增 API 通道 — {}", request.getDisplayName());
        return ResponseEntity.ok(channelRegistryService.createApiChannel(request));
    }

    /**
     * 删除自定义 API 通道。
     *
     * <pre>DELETE /api/profiles/channels/{channelId}</pre>
     */
    @DeleteMapping("/{channelId}")
    public ResponseEntity<Void> deleteChannel(@PathVariable String channelId) {
        log.info("🗑️ 删除通道 {}", channelId);
        channelRegistryService.deleteChannel(channelId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 检测通道 API 连通性。
     *
     * <pre>POST /api/profiles/channels/{channelId}/test</pre>
     */
    @PostMapping("/{channelId}/test")
    public ResponseEntity<ThirdPartyApiTestResponse> testChannel(
            @PathVariable String channelId,
            @RequestBody(required = false) ChannelUpdateRequest request) {
        return ResponseEntity.ok(channelRegistryService.testChannel(channelId, request));
    }
}
