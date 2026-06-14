package com.debatearena.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 研讨进度步骤 DTO，供前端时间线展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgressStepDto {

    /** 步骤标识。 */
    private String id;

    /** 步骤标题。 */
    private String label;

    /** 补充说明。 */
    private String detail;

    /**
     * 步骤状态：pending / active / done / error。
     */
    private String state;

    @Builder.Default
    private List<ProgressStepDto> children = new ArrayList<>();
}
