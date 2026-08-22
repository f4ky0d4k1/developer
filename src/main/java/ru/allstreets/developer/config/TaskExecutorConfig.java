package ru.allstreets.developer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Конфигурация пула потоков для выполнения задач.
 * 10 потоков, одна задача = один поток.
 * При переполнении очереди — задача отклоняется (backpressure), логируется.
 * CallerRunsPolicy НЕ используется — не блокирует polling поток.
 */
@Configuration
public class TaskExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutorConfig.class);

    @Bean("taskExecutor")
    public ThreadPoolExecutor taskExecutor() {
        return new ThreadPoolExecutor(
                10,
                10,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "agent-task-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                (runnable, executor) -> {
                    int active = executor.getActiveCount();
                    int queueSize = executor.getQueue().size();
                    log.error("TaskExecutor: REJECTED — пул переполнен (active={}, queue={}/{}). Backpressure.",
                            active, queueSize, executor.getQueue().remainingCapacity() + queueSize);
                    throw new RejectedExecutionException(
                            "Task pool exhausted: active=" + active + ", queue=" + queueSize);
                }
        );
    }
}
