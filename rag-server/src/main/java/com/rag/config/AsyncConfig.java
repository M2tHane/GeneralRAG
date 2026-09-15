package com.rag.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSE 专用线程设施（实现计划 Task 5；本机 JDK 17 不启用虚拟线程，
 * 固定大小线程池行为等价，见实现计划"环境适配"）。
 *
 * <p>并发上限 {@link #SSE_STREAM_CONCURRENCY}=32：ChatStreamService 以
 * Semaphore 在受理阶段同步把关（超出 → 503 SERVICE_UNAVAILABLE），
 * executor 的 AbortPolicy 作为第二道防线。</p>
 */
@Configuration
public class AsyncConfig {

    /** SSE 并发流上限（评审修复项：SSE executor 上限；不新增配置键，先以常量收敛）。 */
    public static final int SSE_STREAM_CONCURRENCY = 32;

    /** SSE 心跳 :ping 周期（秒），与契约"每 15 秒"一致。 */
    public static final int SSE_HEARTBEAT_SECONDS = 15;

    @Bean(name = "sseStreamExecutor")
    public Executor sseStreamExecutor() {
        ThreadFactory factory = namedDaemonFactory("sse-stream-");
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                SSE_STREAM_CONCURRENCY, SSE_STREAM_CONCURRENCY,
                60L, java.util.concurrent.TimeUnit.SECONDS,
                new SynchronousQueue<>(), factory,
                (RejectedExecutionHandler) (r, e) -> {
                    throw new IllegalStateException("SSE 线程池已满（并发上限 " + SSE_STREAM_CONCURRENCY + "）");
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** 心跳与模型空闲超时看门狗共用的小型调度池（守护线程，随上下文关闭）。 */
    @Bean(name = "sseScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService sseScheduler() {
        return Executors.newScheduledThreadPool(4, namedDaemonFactory("sse-sched-"));
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    // CGLIB 配置类增强需要可见构造器（private 会让 ConfigurationClassEnhancer 失败）
    AsyncConfig() {
    }
}
