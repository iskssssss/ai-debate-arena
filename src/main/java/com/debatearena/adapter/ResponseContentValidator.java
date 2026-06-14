package com.debatearena.adapter;

import com.debatearena.model.RoundType;

/**
 * 讨论方回复内容校验器 —— 识别误抓用户消息、需求复述等无效回复。
 */
public final class ResponseContentValidator {

    /** 初始方案轮最低有效字数。 */
    private static final int MIN_INITIAL_LENGTH = 400;
    /** 审阅/修订/收敛轮最低有效字数。 */
    private static final int MIN_OTHER_ROUND_LENGTH = 150;

    private ResponseContentValidator() {
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
        int minLen = roundType == RoundType.INITIAL ? MIN_INITIAL_LENGTH : MIN_OTHER_ROUND_LENGTH;
        if (normalized.length() < minLen) {
            return false;
        }
        if (isEchoOf(normalized, prompt) || isEchoOf(normalized, topic)) {
            return false;
        }
        // 初始方案须包含结构化章节痕迹，避免只复述需求标题
        if (roundType == RoundType.INITIAL && !hasInitialStructure(normalized)) {
            return false;
        }
        return true;
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
