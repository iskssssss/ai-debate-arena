package com.debatearena.reporting;

import org.springframework.stereotype.Component;

/**
 * Markdown 格式化工具 —— 处理转义、表格生成、代码块、轮次标题等。
 */
@Component
public class MarkdownRenderer {

    /**
     * 转义 Markdown 特殊字符。
     */
    public String escape(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("#", "\\#")
                .replace("+", "\\+")
                .replace("-", "\\-")
                .replace(".", "\\.")
                .replace("!", "\\!");
    }

    /**
     * 生成 Markdown 表格。
     *
     * @param headers  列标题
     * @param rows     数据行
     * @return 格式化的 Markdown 表格
     */
    public String table(String[] headers, String[][] rows) {
        StringBuilder sb = new StringBuilder();

        // 表头
        sb.append("| ");
        for (String h : headers) {
            sb.append(h).append(" | ");
        }
        sb.append("\n");

        // 分隔线
        sb.append("|");
        for (int i = 0; i < headers.length; i++) {
            sb.append("------|");
        }
        sb.append("\n");

        // 数据行
        for (String[] row : rows) {
            sb.append("| ");
            for (String cell : row) {
                sb.append(cell != null ? cell : "").append(" | ");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 生成代码块。
     */
    public String codeBlock(String language, String code) {
        return "```" + (language != null ? language : "") + "\n" + code + "\n```\n";
    }

    /**
     * 生成轮次标题。
     */
    public String roundHeading(int roundNumber, String roundType) {
        return "### 第 " + roundNumber + " 轮：" + roundType + "\n";
    }

    /**
     * 生成引用块。
     */
    public String blockquote(String content) {
        return "> " + content.replace("\n", "\n> ") + "\n";
    }

    /**
     * 截断并添加省略号。
     */
    public String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "\n\n*（内容过长已截断...）*";
    }
}
