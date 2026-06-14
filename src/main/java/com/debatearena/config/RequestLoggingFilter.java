package com.debatearena.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 请求/响应调试日志过滤器。
 * <p>
 * 在 debug profile 下记录每个 API 调用的方法、路径、耗时和响应状态码，
 * 便于排查 REST 接口问题。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_LOG_LENGTH = 500;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startMs = System.currentTimeMillis();
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String query = request.getQueryString();

        log.debug("➡️  HTTP 请求 — {} {}{}",
                method, uri, query != null ? "?" + query : "");

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            long elapsed = System.currentTimeMillis() - startMs;
            int status = wrappedResponse.getStatus();

            if (log.isDebugEnabled() && isJsonRequest(wrappedRequest)) {
                String body = extractBody(wrappedRequest.getContentAsByteArray());
                if (!body.isBlank()) {
                    log.debug("   请求体: {}", truncate(body));
                }
            }

            log.debug("⬅️  HTTP 响应 — {} {} → {} ({}ms)",
                    method, uri, status, elapsed);

            wrappedResponse.copyBodyToResponse();
        }
    }

    /**
     * 判断是否为 JSON 请求（仅记录 JSON 请求体，避免日志过大）。
     */
    private boolean isJsonRequest(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.contains("application/json");
    }

    /**
     * 从字节数组提取 UTF-8 字符串。
     */
    private String extractBody(byte[] content) {
        if (content == null || content.length == 0) {
            return "";
        }
        return new String(content, StandardCharsets.UTF_8).trim();
    }

    /**
     * 截断过长内容，防止日志刷屏。
     */
    private String truncate(String text) {
        if (text.length() <= MAX_BODY_LOG_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_BODY_LOG_LENGTH) + "...[截断]";
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 跳过静态资源和健康探针
        return path.startsWith("/actuator")
                || path.endsWith(".css")
                || path.endsWith(".js")
                || path.endsWith(".ico");
    }
}
