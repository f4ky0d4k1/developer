package ru.allstreets.developer.checkpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-task блокировка на PostgreSQL session-level advisory lock.
 * Переживает падение/рестарт приложения (сессия БД держит lock до close()
 * соединения) и корректно работает при нескольких инстансах приложения
 * на одну БД — в отличие от прежнего in-memory ReentrantLock.
 * Используется AgentGraphRunner и PrCommentMonitor.
 */
@Service
public class TaskLockService {

    private static final Logger log = LoggerFactory.getLogger(TaskLockService.class);

    private final DataSource dataSource;
    private final ConcurrentHashMap<String, Connection> heldConnections = new ConcurrentHashMap<>();

    public TaskLockService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Захватить блокировку на задачу.
     *
     * @param taskId ID задачи
     * @return true если блокировка захвачена, false если уже занята
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean tryLock(String taskId) {
        long key = lockKey(taskId);
        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    boolean acquired = rs.getBoolean(1);
                    if (acquired) {
                        heldConnections.put(taskId, conn);
                        log.info("TaskLock: захвачена блокировка для task={}", taskId);
                    } else {
                        conn.close();
                        log.warn("TaskLock: блокировка для task={} уже занята — другой агент работает", taskId);
                    }
                    return acquired;
                }
            }
        } catch (SQLException e) {
            log.error("TaskLock: ошибка захвата блокировки для task={}: {}", taskId, e.getMessage(), e);
            closeQuietly(conn);
            return false;
        }
    }

    /**
     * Освободить блокировку.
     */
    public void unlock(String taskId) {
        Connection conn = heldConnections.remove(taskId);
        if (conn == null) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, lockKey(taskId));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                log.info("TaskLock: освобождена блокировка для task={} (released={})", taskId, rs.getBoolean(1));
            }
        } catch (SQLException e) {
            log.warn("TaskLock: ошибка освобождения блокировки для task={}: {}", taskId, e.getMessage());
        } finally {
            closeQuietly(conn);
        }
    }

    /**
     * Проверить, заблокирована ли задача — видно across все инстансы приложения,
     * т.к. читает {@code pg_locks} напрямую, а не локальное состояние JVM.
     */
    public boolean isLocked(String taskId) {
        long key = lockKey(taskId);
        long classId = key >>> 32;
        long objId = key & 0xFFFFFFFFL;
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_locks " +
                "WHERE locktype = 'advisory' AND classid = ? AND objid = ? AND objsubid = 1 AND granted)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, classId);
            ps.setLong(2, objId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            log.warn("TaskLock: ошибка проверки блокировки для task={}: {}", taskId, e.getMessage());
            return heldConnections.containsKey(taskId);
        }
    }

    /**
     * Очистить блокировку (вызывается после завершения/прерывания задачи).
     * Идемпотентно — безопасно вызывать, даже если блокировка не бралась в этом процессе.
     */
    public void cleanup(String taskId) {
        unlock(taskId);
    }

    private static long lockKey(String taskId) {
        try {
            return UUID.fromString(taskId).getMostSignificantBits();
        } catch (IllegalArgumentException e) {
            return fnv1a64(taskId);
        }
    }

    private static long fnv1a64(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static void closeQuietly(Connection conn) {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException ignored) {
            // соединение всё равно будет закрыто пулом по тайм-ауту
        }
    }
}
