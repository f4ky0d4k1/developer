package ru.allstreets.developer.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.checkpoint.PendingInputRepository;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskLockService;
import ru.allstreets.developer.checkpoint.TaskRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Периодическая проверка задач, помеченных RUNNING в БД, на предмет "зомби" —
 * задач, для которых нет ни живого потока в этом инстансе ({@link TaskLauncher#isRunning}),
 * ни активной advisory-блокировки ({@link TaskLockService#isLocked}, видна across инстансы).
 * Такие задачи возникают при падении/убийстве процесса приложения посреди выполнения узла графа
 * (checkpoint сохранён, но раннер не успел ни продолжить, ни пометить FAILED).
 * <p>
 * HITL-пауза (задача RUNNING, ждёт ответа пользователя, есть pending-вопрос) сюда НЕ относится —
 * её не перезапускаем, иначе агент уходит дальше без ответа (см. {@link PendingInputRepository}).
 * <p>
 * Раньше это только детектировалось по явному запросу пользователя в Telegram
 * ({@code TaskMcpTools.getTaskDetails}, пометка "⚠️ ZOMBIE"), без автоматического восстановления.
 */
@Component
public class ZombieTaskMonitor {

    private static final Logger log = LoggerFactory.getLogger(ZombieTaskMonitor.class);

    /**
     * Не трогаем задачи моложе этого порога — избегаем гонки с ещё не стартовавшим потоком.
     */
    private static final long GRACE_PERIOD_SECONDS = 90;

    private final TaskRepository taskRepo;
    private final TaskLauncher taskLauncher;
    private final TaskLockService taskLockService;
    private final PendingInputRepository pendingInputRepo;

    public ZombieTaskMonitor(TaskRepository taskRepo, TaskLauncher taskLauncher, TaskLockService taskLockService,
                             PendingInputRepository pendingInputRepo) {
        this.taskRepo = taskRepo;
        this.taskLauncher = taskLauncher;
        this.taskLockService = taskLockService;
        this.pendingInputRepo = pendingInputRepo;
    }

    @Scheduled(fixedDelay = 120_000, initialDelay = 90_000)
    public void checkForZombies() {
        var runningTasks = taskRepo.findByStatus("RUNNING");
        if (runningTasks.isEmpty()) {
            return;
        }

        Instant cutoff = Instant.now().minus(GRACE_PERIOD_SECONDS, ChronoUnit.SECONDS);

        for (TaskEntity task : runningTasks) {
            String taskId = task.getTaskId();

            if (task.getUpdatedAt() != null && task.getUpdatedAt().isAfter(cutoff)) {
                continue; // слишком свежая — даём шанс стартовать
            }

            // HITL-пауза: задача RUNNING, но намеренно ждёт ответа пользователя (pending-вопрос),
            // живого потока и блокировки нет. Это не зомби — поднимать её самим нельзя.
            if (pendingInputRepo.existsById(taskId)) {
                log.debug("ZombieTaskMonitor: задача {} ждёт ответа пользователя (HITL) — пропуск", taskId);
                continue;
            }

            if (taskLauncher.isRunning(taskId)) {
                continue; // живой поток в этом инстансе
            }

            if (taskLockService.isLocked(taskId)) {
                continue; // выполняется где-то (другой инстанс, либо в процессе завершения)
            }

            log.warn("ZombieTaskMonitor: обнаружена зомби-задача {} (статус RUNNING, нет потока, нет блокировки) — перезапуск",
                    taskId);

            Long chatId = task.getNotifyChatId();
            if (chatId == null) {
                log.error("ZombieTaskMonitor: у зомби-задачи {} нет notifyChatId — восстановление невозможно, помечаю FAILED",
                        taskId);
                task.setStatus("FAILED");
                task.setUpdatedAt(Instant.now());
                taskRepo.save(task);
                continue;
            }

            boolean restarted = taskLauncher.restart(taskId, chatId,
                    "Автоматическое восстановление после обнаружения зависшей задачи.");
            if (!restarted) {
                log.error("ZombieTaskMonitor: не удалось перезапустить зомби-задачу {} — checkpoint отсутствует, помечаю FAILED",
                        taskId);
                task.setStatus("FAILED");
                task.setUpdatedAt(Instant.now());
                taskRepo.save(task);
            }
        }
    }
}
