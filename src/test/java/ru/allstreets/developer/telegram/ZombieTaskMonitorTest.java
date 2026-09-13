package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.checkpoint.PendingInputRepository;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskLockService;
import ru.allstreets.developer.checkpoint.TaskRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Зомби-детекция не должна путать зависшую задачу с HITL-паузой: задача RUNNING без потока и
 * блокировки, но с pending-вопросом, намеренно ждёт ответа пользователя — перезапускать её нельзя
 * (иначе агент уходит дальше без ответа; инцидент с автоперезапуском 330559f5).
 */
class ZombieTaskMonitorTest {

    private static final String TASK_ID = "330559f5-0000-0000-0000-000000000000";

    private TaskRepository taskRepo;
    private TaskLauncher taskLauncher;
    private TaskLockService taskLockService;
    private PendingInputRepository pendingInputRepo;
    private ZombieTaskMonitor monitor;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        taskLauncher = mock(TaskLauncher.class);
        taskLockService = mock(TaskLockService.class);
        pendingInputRepo = mock(PendingInputRepository.class);
        monitor = new ZombieTaskMonitor(taskRepo, taskLauncher, taskLockService, pendingInputRepo);

        // RUNNING-задача старше grace-периода, без живого потока и без advisory-блокировки.
        TaskEntity stale = new TaskEntity(TASK_ID, "RUNNING", "d", "t", 42L);
        stale.setUpdatedAt(Instant.now().minus(300, ChronoUnit.SECONDS));
        when(taskRepo.findByStatus("RUNNING")).thenReturn(List.of(stale));
        when(taskLauncher.isRunning(TASK_ID)).thenReturn(false);
        when(taskLockService.isLocked(TASK_ID)).thenReturn(false);
    }

    @Test
    void zombieTask_isRestarted() {
        when(pendingInputRepo.existsById(TASK_ID)).thenReturn(false);
        when(taskLauncher.restart(eq(TASK_ID), anyLong(), anyString())).thenReturn(true);

        monitor.checkForZombies();

        verify(taskLauncher).restart(eq(TASK_ID), eq(42L), anyString());
    }

    @Test
    void hitlPausedTask_isNotRestarted() {
        when(pendingInputRepo.existsById(TASK_ID)).thenReturn(true);

        monitor.checkForZombies();

        verify(taskLauncher, never()).restart(anyString(), anyLong(), anyString());
    }
}
