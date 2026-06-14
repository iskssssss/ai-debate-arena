package com.debatearena.prompts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 模板渲染服务 —— 加载 {@code .st} 文件并替换模板变量。
 * <p>
 * 使用简单的 {@code {variableName}} 占位符替换机制，
 * 兼容 Spring AI 的 StringTemplate 语法。
 * <p>
 * 注意：此服务仅用于模板渲染，不调用任何 AI API。
 */
@Slf4j
@Service
public class PromptTemplateService {

    private final ResourceLoader resourceLoader;

    /** 匹配 {variableName} 占位符。 */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{(\\w+)}");

    public PromptTemplateService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * 渲染初始方案 Prompt。
     */
    public String renderInitialPrompt(String topic, String yourAlias, int participantCount) {
        return render("classpath:templates/debate/initial-prompt.st", Map.of(
                "topic", topic,
                "your_alias", yourAlias,
                "participant_count", String.valueOf(participantCount)
        ));
    }

    /**
     * 渲染极简需求的紧凑初始方案 Prompt。
     */
    public String renderCompactInitialPrompt(String topic, String yourAlias, int participantCount) {
        return render("classpath:templates/debate/initial-prompt-compact.st", Map.of(
                "topic", topic,
                "your_alias", yourAlias,
                "participant_count", String.valueOf(participantCount)
        ));
    }

    /**
     * 渲染批判 Prompt —— 根据实际参与的其他讨论方动态生成。
     */
    public String renderCritiquePrompt(String topic, String yourAlias, int participantCount,
                                       String otherDebatersSection, String yourPreviousResponse,
                                       String critiqueInstructions) {
        return render("classpath:templates/debate/critique-prompt.st", Map.of(
                "topic", topic,
                "your_alias", yourAlias,
                "participant_count", String.valueOf(participantCount),
                "other_debaters_section", otherDebatersSection,
                "your_previous_response", yourPreviousResponse,
                "critique_instructions", critiqueInstructions
        ));
    }

    /**
     * 渲染反驳 Prompt。
     */
    public String renderRebuttalPrompt(String topic, String yourAlias,
                                       String yourPreviousResponse, String critiquesOfYourResponse) {
        return render("classpath:templates/debate/rebuttal-prompt.st", Map.of(
                "topic", topic,
                "your_alias", yourAlias,
                "your_previous_response", yourPreviousResponse,
                "critiques_of_your_response", critiquesOfYourResponse
        ));
    }

    /**
     * 渲染收敛确认 Prompt。
     */
    public String renderConvergencePrompt(String topic, String yourAlias,
                                          String yourCurrentPosition, String otherDebatersPositions) {
        return render("classpath:templates/debate/convergence-check.st", Map.of(
                "topic", topic,
                "your_alias", yourAlias,
                "your_current_position", yourCurrentPosition,
                "other_debaters_positions", otherDebatersPositions
        ));
    }

    /**
     * 渲染最终报告模板。
     */
    public String renderFinalReport(Map<String, Object> params) {
        return render("classpath:templates/synthesis/final-report.st", params);
    }

    /**
     * 通用模板渲染 —— 加载 .st 文件内容，替换 {variable} 占位符。
     */
    private String render(String templatePath, Map<String, Object> params) {
        try {
            Resource resource = resourceLoader.getResource(templatePath);
            String templateContent = resource.getContentAsString(StandardCharsets.UTF_8);
            String result = replacePlaceholders(templateContent, params);
            log.debug("模板渲染完成: {} → {} 字符, 变量数={}", templatePath, result.length(), params.size());
            return result;
        } catch (IOException e) {
            log.error("模板加载失败: {} — {}", templatePath, e.getMessage());
            throw new RuntimeException("无法加载模板: " + templatePath, e);
        }
    }

    /**
     * 将模板中的 {key} 占位符替换为 params 中的值。
     */
    private String replacePlaceholders(String template, Map<String, Object> params) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = params.containsKey(key)
                    ? String.valueOf(params.get(key))
                    : matcher.group(0); // 未找到则保留原样
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
