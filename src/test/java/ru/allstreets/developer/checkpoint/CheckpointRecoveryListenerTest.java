package ru.allstreets.developer.checkpoint;

import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentError;
import io.github.asekka.springai.agents.core.AgentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.config.AgentGraphRunner;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Восстановление при рестарте: уже завершённую задачу не поднимаем (устаревший checkpoint
 * чистим), а провал возобновления <b>уведомляем</b> в Telegram — раньше задача «тихо умирала».
 */
class CheckpointRecoveryListenerTest {

    private static final String RUN_ID = "11111111-1111-1111-1111-111111111111";

    private CheckpointService checkpointService;
    private AgentGraphRunner graphRunner;
    private TelegramGateway telegram;
    private TaskRepository taskRepo;
    private CheckpointRecoveryListener listener;

    @BeforeEach
    void setUp() {
        checkpointService = mock(CheckpointService.class);
        graphRunner = mock(AgentGraphRunner.class);
        telegram = mock(TelegramGateway.class);
        taskRepo = mock(TaskRepository.class);
        listener = new CheckpointRecoveryListener(checkpointService, graphRunner, telegram, taskRepo);

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
        verify(graphRunner, never()).resume(anyString());
    }

    @Test
    void resumeFailure_isNotifiedToTelegram() {
        when(graphRunner.resume(RUN_ID))
                .thenReturn(AgentResult.failed(AgentError.of("analyst", new IllegalStateException("repo missing"))));

        listener.recoverUnfinishedTasks();

        verify(telegram).sendMessage(eq(42L), contains("не удалось"));
        verify(telegram).sendMessage(eq(42L), contains("repo missing"));
    }

    @Test
    void resumeSuccess_noFailureNotification() {
        when(graphRunner.resume(RUN_ID)).thenReturn(AgentResult.ofText("ok"));

        listener.recoverUnfinishedTasks();

        verify(telegram, never()).sendMessage(anyLong(), contains("не удалось"));
    }
}
