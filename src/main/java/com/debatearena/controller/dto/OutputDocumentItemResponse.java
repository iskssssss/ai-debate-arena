package com.debatearena.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 产出文档列表项。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutputDocumentItemResponse {

    /** 文档类型 ID。 */
    private String type;

    /** 文档中文标题。 */
    private String title;

    /** 文档用途说明。 */
    private String description;

    /** 生成状态：pending / ready / failed。 */
    private String status;

    /** 失败原因（status=failed 时）。 */
    private String errorMessage;

    /** 生成完成时间。 */
    private String generatedAt;
}
