package com.debatearena.controller.dto;

import lombok.Data;

/**
 * 新增自定义 API 通道请求。
 */
@Data
public class ChannelCreateRequest {

    private String displayName;
    private String baseUrl;
    private String apiKey;
    private String model;
}
