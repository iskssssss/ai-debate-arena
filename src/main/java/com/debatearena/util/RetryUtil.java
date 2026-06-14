package com.debatearena.util;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * 通用重试工具 —— 指数退避，最多重试 3 次。
 * <p>
 * 重试序列：失败 → 等待 1s → 重试 → 等待 2s → 重试 → 等待 4s → 失败则抛异常。
 * 适用于：浏览器 DOM 操作、选择器定位、网络请求等瞬态故障场景。
 */
@Slf4j
public final class RetryUtil {

    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 1000;

    private RetryUtil() {
    }

    /**
     * 执行带重试的可调用任务。
     *
     * @param operation      要执行的操作
     * @param operationName  操作名称（用于日志）
     * @param retryOnFailure 判断哪些异常需要重试
     * @param <T>            返回值类型
     * @return 操作结果
     * @throws RuntimeException 重试耗尽后抛出
     */
    public static <T> T executeWithRetry(Callable<T> operation, String operationName,
                                          Predicate<Exception> retryOnFailure) {
        Exception lastException = null;
        long delay = BASE_DELAY_MS;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (attempt > 1) {
                    log.info("🔄 {} 第 {} 次重试…", operationName, attempt);
                }
                return operation.call();
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES && retryOnFailure.test(e)) {
                    log.warn("{} 失败 (尝试 {}/{}): {} — {}ms 后重试",
                            operationName, attempt, MAX_RETRIES, e.getMessage(), delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试被中断", ie);
                    }
                    delay *= 2; // 指数退避：1s → 2s → 4s
                } else {
                    break;
                }
            }
        }

        throw new RuntimeException(operationName + " 重试 " + MAX_RETRIES + " 次后仍然失败", lastException);
    }

    /**
     * 执行带重试的可运行任务（无返回值）。
     */
    public static void executeWithRetry(Runnable operation, String operationName) {
        executeWithRetry(() -> {
            operation.run();
            return null;
        }, operationName, e -> true);
    }

    /**
     * 执行带重试的任务，对所有异常重试。
     */
    public static <T> T executeWithRetry(Callable<T> operation, String operationName) {
        return executeWithRetry(operation, operationName, e -> true);
    }
}
