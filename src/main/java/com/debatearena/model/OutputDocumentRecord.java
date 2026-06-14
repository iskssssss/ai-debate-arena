package com.debatearena.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 单场研讨产出文档的生成记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutputDocumentRecord {

    /** 文档类型 ID。 */
    private String type;

    /** 文档中文标题。 */
    private String title;

    /** 生成成功的 Markdown 正文。 */
    private String content;

    /** 是否生成成功。 */
    private boolean success;

    /** 失败原因。 */
    private String errorMessage;

    /** 生成完成时间。 */
    private LocalDateTime generatedAt;

    /**
     * 构建成功记录。
     */
    public static OutputDocumentRecord success(String type, String title, String content) {
        return OutputDocumentRecord.builder()
                .type(type)
                .title(title)
                .content(content)
                .success(true)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 构建失败记录。
     */
    public static OutputDocumentRecord failure(String type, String title, String errorMessage) {
        return OutputDocumentRecord.builder()
                .type(type)
                .title(title)
                .success(false)
                .errorMessage(errorMessage)
                .generatedAt(LocalDateTime.now())
                .build();
    }
}
