package com.debatearena.adapter;

import com.debatearena.model.RoundType;

/**
 * 讨论方回复内容校验器 —— 识别误抓用户消息、需求复述等无效回复。
 */
public final class ResponseContentValidator {

    /** 初始方案轮最低有效字数。 */
    private static final int MIN_INITIAL_LENGTH = 400;
    /** 极简需求初始方案最低有效字数。 */
    private static final int MIN_COMPACT_INITIAL_LENGTH = 120;
    /** 审阅/修订/收敛轮最低有效字数。 */
    private static final int MIN_OTHER_ROUND_LENGTH = 150;

    private ResponseContentValidator() {
    }

    /**
     * 去除平台 UI 提取时混入的前缀（如「Gemini 说」）。
     *
     * @param content 原始提取文本
     * @return 清洗后的文本
     */
    public static String sanitizePlatformUiArtifacts(String content) {
        if (content == null || content.isBlank()) {
            return content == null ? "" : content;
        }
        String result = content.strip();
        result = result.replaceFirst(
                "(?is)^(?:Gemini|ChatGPT|DeepSeek|Bard)\\s*(?:说|said)[:：\\s]*\\n?", "");
        result = result.replaceFirst("(?m)^你说\\s*$", "");
        result = result.replaceFirst("(?m)^(?:复制|Copy|下载|Download)\\s*$", "");
        return result.strip();
    }

    /**
     * 判断提取到的文本是否为有效讨论方回复。
     *
     * @param content  提取结果
     * @param prompt   本轮发送的完整提示词
     * @param topic    研讨需求主题
     * @param roundType 轮次类型
     * @return true 表示可接受
     */
    public static boolean isValid(String content, String prompt, String topic, RoundType roundType) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String normalized = normalize(content);
        int minLen = roundType == RoundType.INITIAL
                ? (isCompactTopic(topic) ? MIN_COMPACT_INITIAL_LENGTH : MIN_INITIAL_LENGTH)
                : MIN_OTHER_ROUND_LENGTH;
        if (normalized.length() < minLen) {
            return false;
        }
        if (isEchoOf(normalized, prompt) || isEchoOf(normalized, topic)) {
            return false;
        }
        // 初始方案须包含结构化章节痕迹，避免只复述需求标题或中途截断
        if (roundType == RoundType.INITIAL) {
            if (!hasInitialStructure(normalized)) {
                return false;
            }
            if (!isCompactTopic(topic) && looksTruncated(normalized)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断需求是否属于极简场景（与 DebatePromptBuilder 使用相同启发式）。
     */
    public static boolean isCompactTopic(String topic) {
        if (topic == null) {
            return false;
        }
        String trimmed = topic.trim();
        if (trimmed.isEmpty() || trimmed.length() > 40) {
            return false;
        }
        if (trimmed.matches("^[\\d+\\-*/().=\\s]+$")) {
            return true;
        }
        return trimmed.length() <= 20
                && !trimmed.matches(".*(系统|服务|平台|架构|模块|接口|数据库|微服务|部署|API).*");
    }

    /**
     * 判断初始方案是否疑似流式输出未完成即被截断。
     */
    public static boolean looksTruncated(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String tail = content.substring(Math.max(0, content.length() - 40)).trim();
        if (tail.endsWith("（") || tail.endsWith("：") || tail.endsWith("，")
                || tail.endsWith("、") || tail.endsWith("之间") || tail.endsWith("请求")) {
            return true;
        }
        boolean hasMidSections = content.contains("接口") || content.contains("技术选型")
                || content.contains("模块划分") || content.matches("(?s).*##\\s*5\\..*");
        boolean hasTailSections = content.contains("实施计划") || content.contains("风险与缓解")
                || content.contains("关键流程") || content.contains("验收方式")
                || content.contains("风险与注意点")
                || content.matches("(?s).*##\\s*[6789]\\..*");
        return hasMidSections && !hasTailSections && content.length() < 2200;
    }

    /**
     * 从提示词中解析需求主题片段（用于回声检测）。
     */
    public static String extractTopicFromPrompt(String prompt) {
        if (prompt == null) {
            return "";
        }
        String marker = "=== 需求描述 ===";
        int start = prompt.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = prompt.indexOf("================", start);
        if (end < 0) {
            end = prompt.length();
        }
        return prompt.substring(start, end).trim();
    }

    /**
     * 根据提示词内容推断轮次类型（供适配器校验使用）。
     */
    public static RoundType inferRoundType(String prompt) {
        if (prompt == null) {
            return RoundType.INITIAL;
        }
        if (prompt.contains("审阅原则") || prompt.contains("方案的审阅")) {
            return RoundType.CRITIQUE;
        }
        if (prompt.contains("修订原则") || prompt.contains("意见回应")) {
            return RoundType.REBUTTAL;
        }
        if (prompt.contains("收敛输出纪律") || prompt.contains("方案共识评估")) {
            return RoundType.CONVERGENCE;
        }
        return RoundType.INITIAL;
    }

    /**
     * 判断回复是否实质重复提示词或需求原文。
     */
    private static boolean isEchoOf(String normalizedContent, String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String normalizedSource = normalize(source);
        if (normalizedSource.length() < 30) {
            return false;
        }
        if (normalizedContent.equals(normalizedSource)) {
            return true;
        }
        if (normalizedContent.contains(normalizedSource) && normalizedSource.length() >= 80) {
            return true;
        }
        // 高重叠：较短文本几乎完全被较长文本包含
        int overlap = longestCommonSubstringLen(normalizedContent, normalizedSource);
        int shorter = Math.min(normalizedContent.length(), normalizedSource.length());
        return shorter > 0 && (double) overlap / shorter >= 0.85;
    }

    /**
     * 初始方案应出现章节标题或编号结构。
     */
    private static boolean hasInitialStructure(String content) {
        return content.contains("需求理解")
                || content.contains("实现方案")
                || content.contains("推荐实现")
                || content.contains("验收方式")
                || content.contains("模块划分")
                || content.contains("技术选型")
                || content.matches("(?s).*##\\s*\\d+\\..*");
    }

    /**
     * 归一化空白与标点，便于比较。
     */
    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * 计算两串最长公共子串长度（用于回声检测）。
     */
    private static int longestCommonSubstringLen(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int max = 0;
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                    max = Math.max(max, dp[i][j]);
                }
            }
        }
        return max;
    }
}
