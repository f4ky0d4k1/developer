package ru.allstreets.developer.opencode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
    private final int slotCount;
    private final AtomicBoolean[] slotOccupied;

    public OpenCodeSessionPool(WorktreeManager worktreeManager) {
        this.worktreeManager = worktreeManager;
        this.slotCount = worktreeManager.getSlotCount();
        this.semaphore = new Semaphore(slotCount, true);
        this.slotOccupied = new AtomicBoolean[slotCount];
        for (int i = 0; i < slotCount; i++) {
            slotOccupied[i] = new AtomicBoolean(false);
        }
        log.info("OpenCode пул инициализирован: {} слотов", slotCount);
    }

    /**
     * Занять свободный слот. Блокируется до освобождения.
     *
     * @param timeoutSeconds таймаут ожидания
     * @return индекс слота или -1 при таймауте
     */
    public int acquire(long timeoutSeconds) {
        try {
            log.info("Ожидание свободного слота OpenCode (таймаут {}с)...", timeoutSeconds);
            if (!semaphore.tryAcquire(timeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("Таймаут ожидания слота OpenCode");
                return -1;
            }

            // Находим свободный слот
            for (int i = 0; i < slotCount; i++) {
                if (slotOccupied[i].compareAndSet(false, true)) {
                    log.info("Слот {} занят", i);
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
    public void prepareSlot(int slotIndex) {
        worktreeManager.prepareSlot(slotIndex);
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
