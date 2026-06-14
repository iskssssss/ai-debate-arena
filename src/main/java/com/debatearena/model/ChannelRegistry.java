package com.debatearena.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 本机通道注册表（内置 + 自定义 API 通道）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelRegistry {

    @Builder.Default
    private List<ChannelDefinition> channels = new ArrayList<>();
}
