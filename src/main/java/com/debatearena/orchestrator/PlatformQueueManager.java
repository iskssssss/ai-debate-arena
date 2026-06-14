package com.debatearena.orchestrator;

import com.debatearena.model.AiPlatform;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 平台并发队列管理器 —— 每个 AI 平台一个独立 FIFO 队列。
 * <p>
 * 设计目的：序列化同一平台的并发辩论操作，避免多个辩论会话的消息交叉污染。
 * 每个平台使用单线程执行器，保证同一时刻只有一个操作在给定平台上执行。
 */
@Slf4j
@Component
public class PlatformQueueManager {

    /** 每个平台一个单线程执行器（讨论方专用）。 */
    private final Map<AiPlatform, ExecutorService> platformQueues = new EnumMap<>(AiPlatform.class);

    /** 每个平台一个单线程执行器（整理通道专用，与讨论方队列隔离）。 */
    private final Map<AiPlatform, ExecutorService> judgeQueues = new EnumMap<>(AiPlatform.class);

    @PostConstruct
    void init() {
        for (AiPlatform p : AiPlatform.values()) {
            platformQueues.put(p, newSingleThreadExecutor("platform-" + p.name().toLowerCase()));
            judgeQueues.put(p, newSingleThreadExecutor("judge-" + p.name().toLowerCase()));
        }
        log.info("✅ 平台并发队列已初始化 — 讨论方与整理通道各自独立 FIFO 队列");
    }

    /**
     * 创建平台专用单线程执行器。
     */
    private ExecutorService newSingleThreadExecutor(String threadName) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 在指定平台的整理通道队列中提交任务（与讨论方队列隔离）。
     */
    public <T> CompletableFuture<T> submitForJudge(AiPlatform platform, Callable<T> task) {
        ExecutorService queue = judgeQueues.get(platform);
        if (queue == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("未注册的整理通道平台: " + platform));
        }
        log.debug("📥 整理任务入队 — platform={}, queueDepth≈{}", platform.name(), getQueueDepth(queue));
        return submitOnQueue(queue, platform.name() + "-judge", task);
    }

    /**
     * 在指定平台的队列中提交任务，返回 CompletableFuture。
     *
     * @param platform 目标 AI 平台
     * @param task     要执行的任务
     * @param <T>      返回值类型
     * @return 异步结果
     */
    public <T> CompletableFuture<T> submit(AiPlatform platform, Callable<T> task) {
        ExecutorService queue = platformQueues.get(platform);
        if (queue == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("未注册的平台: " + platform));
        }
        log.debug("📥 任务入队 — platform={}, queueDepth≈{}", platform.name(), getQueueDepth(queue));
        return submitOnQueue(queue, platform.name(), task);
    }

    /**
     * 在指定执行器上提交任务并封装为 CompletableFuture。
     */
    private <T> CompletableFuture<T> submitOnQueue(ExecutorService queue, String label, Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        queue.submit(() -> {
            log.debug("⚙️ 任务开始执行 — {}", label);
            try {
                T result = task.call();
                future.complete(result);
                log.debug("✅ 任务执行完成 — {}", label);
            } catch (Exception e) {
                log.debug("❌ 任务执行失败 — {}: {}", label, e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * 在指定平台的队列中提交无返回值的任务。
     */
    public CompletableFuture<Void> submit(AiPlatform platform, Runnable task) {
        return submit(platform, () -> {
            task.run();
            return null;
        });
    }

    /**
     * 获取指定平台队列的当前深度（待处理任务数，仅估算）。
     */
    public int getQueueDepth(AiPlatform platform) {
        return getQueueDepth(platformQueues.get(platform));
    }

    /**
     * 获取指定执行器队列的当前深度（待处理任务数，仅估算）。
     */
    private int getQueueDepth(ExecutorService queue) {
        if (queue instanceof ThreadPoolExecutor tpe) {
            return tpe.getQueue().size();
        }
        return 0;
    }

    @PreDestroy
    void destroy() {
        log.info("🔒 关闭平台并发队列…");
        shutdownQueues(platformQueues);
        shutdownQueues(judgeQueues);
        log.info("✅ 平台并发队列已关闭");
    }

    /**
     * 优雅关闭一组执行器队列。
     */
    private void shutdownQueues(Map<AiPlatform, ExecutorService> queues) {
        for (ExecutorService executor : queues.values()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
