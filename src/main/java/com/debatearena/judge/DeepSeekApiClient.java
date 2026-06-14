package com.debatearena.judge;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.debatearena.config.JudgeConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * DeepSeek Chat Completions API 客户端（OpenAI 兼容格式）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekApiClient {

    private static final int MAX_RETRIES = 3;
    private static final int MAX_USER_PROMPT_CHARS = 48_000;

    private final JudgeConfig judgeConfig;

    /** 串行化 API 调用，避免多场次并发时触发限流导致空响应。 */
    private final Semaphore apiSemaphore = new Semaphore(1);

    /**
     * 调用对话 API 并返回 assistant 回复文本，失败时自动重试。
     */
    public String chat(String apiKey, String model, String systemPrompt, String userPrompt) {
        String trimmedUserPrompt = trimUserPrompt(userPrompt);
        IllegalStateException lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                apiSemaphore.acquire();
                try {
                    return doChat(apiKey, model, systemPrompt, trimmedUserPrompt);
                } finally {
                    apiSemaphore.release();
                }
            } catch (IllegalStateException e) {
                lastError = e;
                if (!isRetryable(e) || attempt == MAX_RETRIES) {
                    throw e;
                }
                long backoffMs = 1500L * attempt;
                log.warn("DeepSeek API 调用失败，{}ms 后重试 ({}/{}): {}",
                        backoffMs, attempt, MAX_RETRIES, e.getMessage());
                sleepQuietly(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DeepSeek API 调用被中断", e);
            }
        }
        throw lastError != null ? lastError : new IllegalStateException("DeepSeek API 调用失败");
    }

    /**
     * 执行单次 HTTP 请求。
     */
    private String doChat(String apiKey, String model, String systemPrompt, String userPrompt) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(30));
        factory.setReadTimeout(Duration.ofSeconds(judgeConfig.getTimeoutSeconds()));

        RestClient client = RestClient.builder()
                .baseUrl(judgeConfig.getBaseUrl())
                .requestFactory(factory)
                .build();

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "stream", false
        );

        log.debug("调用 DeepSeek API — model={}, userPromptLen={}", model, userPrompt.length());

        String responseBody = client.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);

        return extractContent(responseBody);
    }

    /**
     * 限制用户提示词长度，避免超长上下文导致模型返回空内容。
     */
    private String trimUserPrompt(String userPrompt) {
        if (userPrompt == null) {
            return "";
        }
        if (userPrompt.length() <= MAX_USER_PROMPT_CHARS) {
            return userPrompt;
        }
        log.warn("用户提示词过长 ({} 字符)，截断至 {} 字符", userPrompt.length(), MAX_USER_PROMPT_CHARS);
        return userPrompt.substring(0, MAX_USER_PROMPT_CHARS) + "\n... (上下文过长已截断)";
    }

    /**
     * 判断异常是否适合重试（空内容、空响应等瞬时问题）。
     */
    private boolean isRetryable(IllegalStateException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("空内容") || msg.contains("空响应") || msg.contains("无 choices"));
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 从 OpenAI 兼容响应 JSON 中提取 assistant 正文。
     */
    private String extractContent(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("DeepSeek API 返回空响应");
        }
        JSONObject json = JSON.parseObject(responseBody);
        if (json.containsKey("error")) {
            JSONObject err = json.getJSONObject("error");
            String msg = err != null ? err.getString("message") : responseBody;
            throw new IllegalStateException("DeepSeek API 错误: " + msg);
        }
        JSONArray choices = json.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("DeepSeek API 响应无 choices");
        }
        JSONObject choice = choices.getJSONObject(0);
        String finishReason = choice.getString("finish_reason");
        JSONObject message = choice.getJSONObject("message");
        if (message == null) {
            throw new IllegalStateException("DeepSeek API 响应无 message");
        }

        String content = firstNonBlank(
                message.getString("content"),
                message.getString("reasoning_content")
        );

        if (content == null || content.isBlank()) {
            log.warn("DeepSeek API 返回空内容 — finish_reason={}, messageKeys={}",
                    finishReason, message.keySet());
            String detail = finishReason != null ? "（finish_reason=" + finishReason + "）" : "";
            throw new IllegalStateException("DeepSeek API 返回空内容" + detail);
        }
        return content.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
