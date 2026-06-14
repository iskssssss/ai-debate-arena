package com.debatearena.judge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 产出文档正文清洗器 —— 去除模型常见的寒暄、确认语等，保留纯 Markdown 正文。
 */
public final class DocumentContentSanitizer {

    private static final Pattern FIRST_HEADING = Pattern.compile("(?m)^#\\s+.+");
    private static final Pattern TRAILING_FILLER = Pattern.compile(
            "\n+---\n+(?:如有|若需|希望|以上(?:就是|为)|如需|如果).*$",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** 文末延伸服务话术（如「如果需要，我可以补充…」及后续列表）。 */
    private static final Pattern TRAILING_OFFER_BLOCK = Pattern.compile(
            "(?s)\\n+(?:---+\\s*\\n+)?(?:如果(?:你)?需要|如需(?:进一步)?|若需(?:进一步)?|如需要)[，,:：][\\s\\S]*$",
            Pattern.CASE_INSENSITIVE);

    private DocumentContentSanitizer() {
    }

    /**
     * 清洗 Judge 返回的文档正文，剥离前后非 Markdown 正文内容。
     *
     * @param content 模型原始输出
     * @return 以标题开头的纯 Markdown 正文
     */
    public static String sanitize(String content) {
        if (content == null || content.isBlank()) {
            return content == null ? "" : content;
        }
        String text = content.strip();
        text = unwrapCodeFence(text);
        text = stripLeadingPreamble(text);
        text = stripTrailingFiller(text);
        return text.strip();
    }

    /**
     * 若整篇文档被包在 Markdown 代码块中，则提取块内正文。
     */
    private static String unwrapCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String inner = text.substring(firstNewline + 1);
        if (inner.endsWith("```")) {
            inner = inner.substring(0, inner.length() - 3);
        }
        return inner.strip();
    }

    /**
     * 从首个 Markdown 标题行开始截取，去掉之前的寒暄与说明文字。
     */
    private static String stripLeadingPreamble(String text) {
        Matcher matcher = FIRST_HEADING.matcher(text);
        if (matcher.find()) {
            return text.substring(matcher.start());
        }
        return text;
    }

    /**
     * 去掉文末常见的礼貌性结语与延伸服务提议。
     */
    private static String stripTrailingFiller(String text) {
        Matcher offerMatcher = TRAILING_OFFER_BLOCK.matcher(text);
        if (offerMatcher.find()) {
            text = text.substring(0, offerMatcher.start()).stripTrailing();
        }
        Matcher matcher = TRAILING_FILLER.matcher(text);
        if (matcher.find()) {
            return text.substring(0, matcher.start()).stripTrailing();
        }
        return text;
    }
}
