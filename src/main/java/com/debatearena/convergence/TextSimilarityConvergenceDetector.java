package com.debatearena.convergence;

import com.debatearena.model.ConvergenceResult;
import com.debatearena.model.ParticipantResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于双语分词 + TF-IDF + 最小 pairwise 余弦相似度的收敛检测器。
 * <p>
 * 针对中文研讨材料优化：不再使用 EnglishAnalyzer，避免中文被忽略导致虚高相似度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextSimilarityConvergenceDetector implements ConvergenceDetector {

    private static final Pattern ENGLISH_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_\\-]{1,}");
    private static final Pattern MARKDOWN_CODE = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]+\\)");

    /** 英文否定/转折词。 */
    private static final Pattern EN_NEGATION = Pattern.compile(
            "\\b(disagree|incorrect|wrong|mistaken|flawed|however|but|on the contrary|"
                    + "not true|false|invalid|unconvincing|misleading|fails to|does not|doesn't|"
                    + "overlooks|ignores|problematic|unacceptable)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 中文否定/转折词。 */
    private static final Pattern ZH_NEGATION = Pattern.compile(
            "(不同意|不认同|不正确|有问题|然而|但是|不过|相反|并非如此|并不|无法|难以|欠缺|忽略|遗漏|风险|分歧|反对)");

    /** 高频模板词，对区分方案贡献低，计算前过滤。 */
    private static final Set<String> STOP_TERMS = Set.of(
            "方案", "架构", "技术", "实现", "系统", "模块", "设计", "功能", "需求", "建议",
            "讨论", "分析", "总结", "概述", "流程", "接口", "数据", "服务", "平台", "用户",
            "the", "and", "for", "with", "this", "that", "from", "will", "can", "should"
    );

    private static final double NEGATION_PENALTY_PER_HIT = 0.03;
    private static final double MAX_NEGATION_PENALTY = 0.25;

    @Override
    public ConvergenceResult check(List<ParticipantResponse> responses) {
        if (responses.size() < 2) {
            log.warn("收敛检测需要至少 2 份回答，当前仅有 {} 份", responses.size());
            return ConvergenceResult.builder()
                    .converged(false)
                    .averageSimilarity(0.0)
                    .minPairwiseSimilarity(0.0)
                    .agreedPoints(List.of())
                    .pairwiseScores(Map.of())
                    .build();
        }

        List<Map<String, Integer>> termFreqs = new ArrayList<>();
        Set<String> vocabulary = new LinkedHashSet<>();

        for (ParticipantResponse resp : responses) {
            Map<String, Integer> termFreq = tokenize(preprocess(resp.getContent()));
            termFreqs.add(termFreq);
            vocabulary.addAll(termFreq.keySet());
        }

        Map<String, Double> idfWeights = buildIdfWeights(termFreqs, vocabulary);
        List<Map<String, Double>> tfidfVectors = new ArrayList<>();
        for (Map<String, Integer> termFreq : termFreqs) {
            tfidfVectors.add(toTfidfVector(termFreq, idfWeights));
        }

        int n = responses.size();
        Map<String, Double> pairwiseScores = new LinkedHashMap<>();
        List<Double> similarities = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double rawSim = cosineSimilarity(tfidfVectors.get(i), tfidfVectors.get(j));
                double penalty = computeNegationPenalty(
                        responses.get(i).getContent(), responses.get(j).getContent());
                double adjustedSim = Math.max(0.0, rawSim - penalty);

                String pairKey = responses.get(i).getPlatform().name() + "-" + responses.get(j).getPlatform().name();
                pairwiseScores.put(pairKey, adjustedSim);
                similarities.add(adjustedSim);

                log.debug("{} 相似度: raw={}, penalty={}, adjusted={}",
                        pairKey, format4(rawSim), format4(penalty), format4(adjustedSim));
            }
        }

        double minSimilarity = similarities.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double avgSimilarity = similarities.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        ConvergenceResult result = ConvergenceResult.builder()
                .converged(false)
                .averageSimilarity(avgSimilarity)
                .minPairwiseSimilarity(minSimilarity)
                .pairwiseScores(pairwiseScores)
                .agreedPoints(List.of())
                .build();

        log.info("收敛检测完成 — minPairwise={}, avgPairwise={}, 平台数={}, 词典={}",
                format4(minSimilarity), format4(avgSimilarity), n, vocabulary.size());
        return result;
    }

    /**
     * 预处理文本：去除 Markdown 噪声，保留语义内容。
     */
    private String preprocess(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text;
        cleaned = MARKDOWN_CODE.matcher(cleaned).replaceAll(" ");
        cleaned = MARKDOWN_LINK.matcher(cleaned).replaceAll("$1");
        cleaned = cleaned.replaceAll("#+\\s*", " ");
        cleaned = cleaned.replaceAll("[*_`>|]", " ");
        cleaned = cleaned.replaceAll("\\d+\\.", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned;
    }

    /**
     * 中英文混合分词：英文词 + 中文二元组。
     */
    private Map<String, Integer> tokenize(String text) {
        Map<String, Integer> freq = new HashMap<>();
        if (text == null || text.isBlank()) {
            return freq;
        }

        Matcher englishMatcher = ENGLISH_WORD.matcher(text);
        while (englishMatcher.find()) {
            String term = englishMatcher.group().toLowerCase(Locale.ROOT);
            if (!isStopTerm(term)) {
                freq.merge("en:" + term, 1, Integer::sum);
            }
        }

        StringBuilder cjkBuffer = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch)) {
                cjkBuffer.append(ch);
            } else {
                appendCjkBigrams(cjkBuffer, freq);
                cjkBuffer.setLength(0);
            }
        }
        appendCjkBigrams(cjkBuffer, freq);
        return freq;
    }

    /**
     * 将连续汉字切分为二元组词条。
     */
    private void appendCjkBigrams(StringBuilder cjkBuffer, Map<String, Integer> freq) {
        String segment = cjkBuffer.toString();
        if (segment.length() < 2) {
            return;
        }
        for (int i = 0; i < segment.length() - 1; i++) {
            String bigram = "zh:" + segment.substring(i, i + 2);
            if (!isStopTerm(bigram.substring(3))) {
                freq.merge(bigram, 1, Integer::sum);
            }
        }
    }

    /**
     * 计算 IDF 权重，降低各文档共有模板词的影响。
     */
    private Map<String, Double> buildIdfWeights(List<Map<String, Integer>> termFreqs, Set<String> vocabulary) {
        int docCount = termFreqs.size();
        Map<String, Double> idf = new HashMap<>();
        for (String term : vocabulary) {
            int df = 0;
            for (Map<String, Integer> termFreq : termFreqs) {
                if (termFreq.containsKey(term)) {
                    df++;
                }
            }
            idf.put(term, Math.log((docCount + 1.0) / (df + 1.0)) + 1.0);
        }
        return idf;
    }

    /**
     * 将词频向量转换为 TF-IDF 向量。
     */
    private Map<String, Double> toTfidfVector(Map<String, Integer> termFreq, Map<String, Double> idfWeights) {
        int total = termFreq.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return Map.of();
        }
        Map<String, Double> vector = new HashMap<>();
        for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
            double tf = entry.getValue() / (double) total;
            double weight = idfWeights.getOrDefault(entry.getKey(), 1.0);
            vector.put(entry.getKey(), tf * weight);
        }
        return vector;
    }

    /**
     * 计算两个 TF-IDF 向量之间的余弦相似度。
     */
    private double cosineSimilarity(Map<String, Double> v1, Map<String, Double> v2) {
        if (v1.isEmpty() || v2.isEmpty()) {
            return 0.0;
        }
        Set<String> allTerms = new HashSet<>();
        allTerms.addAll(v1.keySet());
        allTerms.addAll(v2.keySet());

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (String term : allTerms) {
            double a = v1.getOrDefault(term, 0.0);
            double b = v2.getOrDefault(term, 0.0);
            dotProduct += a * b;
            norm1 += a * a;
            norm2 += b * b;
        }

        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * 根据中英文否定/转折词密度对相似度施加惩罚。
     */
    private double computeNegationPenalty(String text1, String text2) {
        long hits = countNegationHits(text1) + countNegationHits(text2);
        return Math.min(hits * NEGATION_PENALTY_PER_HIT, MAX_NEGATION_PENALTY);
    }

    private long countNegationHits(String text) {
        if (text == null) {
            return 0;
        }
        long count = 0;
        Matcher en = EN_NEGATION.matcher(text);
        while (en.find()) {
            count++;
        }
        Matcher zh = ZH_NEGATION.matcher(text);
        while (zh.find()) {
            count++;
        }
        return count;
    }

    private boolean isStopTerm(String term) {
        return term != null && STOP_TERMS.contains(term.toLowerCase(Locale.ROOT));
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private String format4(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
