package com.debatearena.controller.dto;

import com.debatearena.model.ChannelType;
import lombok.Data;

/**
 * 更新通道配置请求。
 */
@Data
public class ChannelUpdateRequest {

    private String displayName;
    private ChannelType type;
    private String baseUrl;
    /** 为空时保留已保存的 Key。 */
    private String apiKey;
    private String model;
    private Boolean enabled;
}
