package ru.allstreets.developer.checkpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-task блокировка — гарантирует что только один агент работает над задачей.
 * In-memory ReentrantLock per taskId + DB status check.
 * Используется AgentGraphRunner и PrCommentMonitor.
 */
@Service
public class TaskLockService {

    private static final Logger log = LoggerFactory.getLogger(TaskLockService.class);

    private final ConcurrentHashMap<String, ReentrantLock> taskLocks = new ConcurrentHashMap<>();

    public TaskLockService() {
    }

    /**
     * Захватить блокировку на задачу.
     *
     * @param taskId ID задачи
     * @return true если блокировка захвачена, false если уже занята
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean tryLock(String taskId) {
        ReentrantLock lock = taskLocks.computeIfAbsent(taskId, k -> new ReentrantLock());
        boolean acquired = lock.tryLock();
        if (acquired) {
            log.info("TaskLock: захвачена блокировка для task={}", taskId);
        } else {
            log.warn("TaskLock: блокировка для task={} уже занята — другой агент работает", taskId);
        }
        return acquired;
    }

    /**
     * Освободить блокировку.
     */
    public void unlock(String taskId) {
        ReentrantLock lock = taskLocks.get(taskId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.info("TaskLock: освобождена блокировка для task={}", taskId);
        }
    }

    /**
     * Проверить, заблокирована ли задача.
     */
    public boolean isLocked(String taskId) {
        ReentrantLock lock = taskLocks.get(taskId);
        return lock != null && lock.isLocked();
    }

    /**
     * Очистить блокировку (вызывается после завершения/прерывания задачи).
     */
    public void cleanup(String taskId) {
        taskLocks.remove(taskId);
    }
}
