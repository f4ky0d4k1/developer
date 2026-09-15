package ru.allstreets.developer.opencode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Пул слотов OpenCode с семафором для параллельного выполнения.
 * Каждый слот = отдельный worktree + независимая OpenCode сессия.
 * <p>
 * acquire() — занимает слот (блокируется если все заняты)
 * release() — освобождает слот
 */
@Component
public class OpenCodeSessionPool {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeSessionPool.class);

    private final Semaphore semaphore;
    private final WorktreeManager worktreeManager;
    private final ru.allstreets.developer.metrics.TaskMetrics metrics;
    private final int slotCount;
    private final AtomicBoolean[] slotOccupied;

    /**
     * taskId → slot: слот закреплён за задачей на всё её время жизни и освобождается только при CLOSED.
     */
    private final Map<String, Integer> taskSlots = new ConcurrentHashMap<>();

    public OpenCodeSessionPool(WorktreeManager worktreeManager, ru.allstreets.developer.metrics.TaskMetrics metrics) {
        this.worktreeManager = worktreeManager;
        this.metrics = metrics;
        this.slotCount = worktreeManager.getSlotCount();
        this.semaphore = new Semaphore(slotCount, true);
        this.slotOccupied = new AtomicBoolean[slotCount];
        for (int i = 0; i < slotCount; i++) {
            slotOccupied[i] = new AtomicBoolean(false);
        }
        metrics.registerSlotGauges(this::activeSlots, slotCount);
        log.info("OpenCode пул инициализирован: {} слотов", slotCount);
    }

    /**
     * Занять свободный слот. Блокируется до освобождения.
     *
     * @param timeoutSeconds таймаут ожидания
     * @return индекс слота или -1 при таймауте
     */
    public int acquire(long timeoutSeconds) {
        long startNanos = System.nanoTime();
        try {
            log.info("Ожидание свободного слота OpenCode (таймаут {}с)...", timeoutSeconds);
            if (!semaphore.tryAcquire(timeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("Таймаут ожидания слота OpenCode");
                metrics.slotWait(java.time.Duration.ofNanos(System.nanoTime() - startNanos));
                return -1;
            }

            // Находим свободный слот
            for (int i = 0; i < slotCount; i++) {
                if (slotOccupied[i].compareAndSet(false, true)) {
                    log.info("Слот {} занят", i);
                    metrics.slotWait(java.time.Duration.ofNanos(System.nanoTime() - startNanos));
                    return i;
                }
            }

            // Не должны сюда попасть, но на всякий случай
            semaphore.release();
            return -1;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Прервано ожидание слота OpenCode");
            return -1;
        }
    }

    /**
     * Занять слот ДЛЯ ЗАДАЧИ: если за задачей слот уже закреплён — вернуть его (сессия/worktree
     * живут всё время задачи), иначе занять свободный, закрепить и ПОДГОТОВИТЬ (clone/checkout
     * main — только один раз, при первом закреплении; повторный prepare сбросил бы worktree).
     * Освобождение — только {@link #releaseForTask(String)} (при закрытии задачи, статус CLOSED).
     *
     * @return индекс слота или -1 при таймауте
     */
    public int acquireForTask(String taskId, String repoUrl, long timeoutSeconds) {
        if (taskId != null) {
            Integer existing = taskSlots.get(taskId);
            if (existing != null) {
                log.info("Слот {} уже закреплён за задачей {}", existing, shortId(taskId));
                return existing;
            }
        }
        int slot = acquire(timeoutSeconds);
        if (slot < 0) {
            return -1;
        }
        if (taskId != null) {
            taskSlots.put(taskId, slot);
        }
        try {
            prepareSlot(slot, repoUrl);
        } catch (RuntimeException e) {
            // Не удалось подготовить — не держим слот залипшим.
            if (taskId != null) {
                taskSlots.remove(taskId);
            }
            release(slot);
            throw e;
        }
        log.info("Слот {} закреплён за задачей {}", slot, taskId != null ? shortId(taskId) : "?");
        return slot;
    }

    /**
     * Освободить и очистить слот, закреплённый за задачей. Вызывается ТОЛЬКО при CLOSED —
     * до этого worktree задачи неприкосновенен.
     */
    public void releaseForTask(String taskId) {
        if (taskId == null) {
            return;
        }
        Integer slot = taskSlots.remove(taskId);
        if (slot == null) {
            log.debug("releaseForTask: за задачей {} слот не закреплён", shortId(taskId));
            return;
        }
        cleanupSlot(slot);
        release(slot);
        log.info("Слот {} освобождён (задача {} закрыта)", slot, shortId(taskId));
    }

    private static String shortId(String taskId) {
        return taskId.length() > 8 ? taskId.substring(0, 8) : taskId;
    }

    /**
     * Сколько слотов занято сейчас — для gauge утилизации.
     */
    private int activeSlots() {
        int n = 0;
        for (AtomicBoolean occupied : slotOccupied) {
            if (occupied.get()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Освободить слот.
     */
    public void release(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= slotCount) {
            log.warn("Попытка освобождения неверного слота: {}", slotIndex);
            return;
        }

        slotOccupied[slotIndex].set(false);
        semaphore.release();
        log.info("Слот {} освобождён", slotIndex);
    }

    /**
     * Подготовить слот: clone на main.
     */
    public void prepareSlot(int slotIndex, String repoUrl) {
        worktreeManager.prepareSlot(slotIndex, repoUrl);
    }

    /**
     * Очистить слот после завершения.
     */
    public void cleanupSlot(int slotIndex) {
        worktreeManager.cleanupSlot(slotIndex);
    }

    /**
     * Получить рабочий каталог слота.
     */
    public String getSlotWorkDir(int slotIndex) {
        return worktreeManager.getSlotWorkDir(slotIndex).toString();
    }

}
