package ru.allstreets.developer.checkpoint;

import io.github.asekka.springai.agents.core.AgentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.TaskLauncher;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Восстановление при рестарте: уже завершённую задачу не поднимаем (устаревший checkpoint чистим),
 * а незавершённую отдаём {@link TaskLauncher#resumeAfterRestart} — общий executor, регистрация в
 * {@code runningTasks}, уведомления об успехе/провале (а не «тихая смерть» на потоке старта).
 */
class CheckpointRecoveryListenerTest {

    private static final String RUN_ID = "11111111-1111-1111-1111-111111111111";

    private CheckpointService checkpointService;
    private TaskLauncher taskLauncher;
    private TaskRepository taskRepo;
    private CheckpointRecoveryListener listener;

    @BeforeEach
    void setUp() {
        checkpointService = mock(CheckpointService.class);
        taskLauncher = mock(TaskLauncher.class);
        taskRepo = mock(TaskRepository.class);
        listener = new CheckpointRecoveryListener(checkpointService, taskLauncher, taskRepo);

        when(checkpointService.getUnfinishedCheckpoints())
                .thenReturn(List.of(new CheckpointEntity("cp-1", RUN_ID, "analyst", "{}", "RUNNING")));
        when(taskRepo.findById(RUN_ID))
                .thenReturn(Optional.of(new TaskEntity(RUN_ID, "RUNNING", "d", "t", 42L)));
        when(checkpointService.restoreCheckpoint(RUN_ID))
                .thenReturn(AgentContext.of("x").with(TaskState.TG_CHAT_ID, "42"));
    }

    @Test
    void completedOrFailedTask_isNotResumed_staleCheckpointCleaned() {
        when(taskRepo.findById(RUN_ID))
                .thenReturn(Optional.of(new TaskEntity(RUN_ID, "FAILED", "d", "t", 42L)));

        listener.recoverUnfinishedTasks();

        verify(checkpointService).cleanup(RUN_ID);
        verify(taskLauncher, never()).resumeAfterRestart(anyString(), anyLong());
    }

    @Test
    void runningTask_isResumedThroughTaskLauncher() {
        listener.recoverUnfinishedTasks();

        verify(taskLauncher).resumeAfterRestart(RUN_ID, 42L);
    }

    @Test
    void checkpointWithoutChatId_isSkipped() {
        when(checkpointService.restoreCheckpoint(RUN_ID)).thenReturn(AgentContext.of("x"));

        listener.recoverUnfinishedTasks();

        verify(taskLauncher, never()).resumeAfterRestart(anyString(), anyLong());
    }

    @Test
    void corruptCheckpoint_isSkipped() {
        when(checkpointService.restoreCheckpoint(RUN_ID)).thenReturn(null);

        listener.recoverUnfinishedTasks();

        verify(taskLauncher, never()).resumeAfterRestart(anyString(), anyLong());
    }

    @Test
    void noUnfinishedCheckpoints_doesNothing() {
        when(checkpointService.getUnfinishedCheckpoints()).thenReturn(List.of());

        listener.recoverUnfinishedTasks();

        verify(taskLauncher, never()).resumeAfterRestart(anyString(), anyLong());
        verify(checkpointService, never()).cleanup(anyString());
    }
}
