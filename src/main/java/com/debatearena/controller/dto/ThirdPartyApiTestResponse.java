package com.debatearena.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 三方 API 连接检测结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThirdPartyApiTestResponse {

    /** 是否连通。 */
    private boolean success;

    /** 结果说明。 */
    private String message;
}
