package com.robot.auth.authn.service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** CAS后的非权威索引清理；队列有界，丢失工作由精确TTL兜底，不延长响应。 */
@Slf4j
@Component
public final class AuthCleanupWorker {
    private static final int QUEUE_CAPACITY = 256;
    private static final long IDLE_SECONDS = 30L;
    private final ThreadPoolExecutor executor;
    private final boolean inline;

    /** Start one bounded background cleanup executor; no thread runs before work is submitted. */
    public AuthCleanupWorker() {
        this(false);
    }

    AuthCleanupWorker(boolean inline) {
        this.inline = inline;
        executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        IDLE_SECONDS,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                        task -> {
                            Thread thread = new Thread(task, "auth-index-cleanup");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Submit nonauthoritative cleanup; a saturated executor retains exact TTL fallback.
     *
     * @param task independent index/token cleanup
     */
    public void submit(Runnable task) {
        if (inline) {
            task.run();
            return;
        }
        try {
            executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException error) {
            log.warn("非权威索引清理队列已满，保留TTL兜底");
        }
    }

    /**
     * Execute nonauthoritative cleanup without converting a committed authentication transition
     * into failure.
     */
    static void bestEffort(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            log.warn("{}失败，族状态仍为鉴权事实源；索引按 TTL 过期", operation);
        }
    }

    /** Stop background cleanup when the application shuts down. */
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
