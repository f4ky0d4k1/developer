package ru.allstreets.developer.telegram;

import io.github.asekka.springai.agents.core.AgentResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import ru.allstreets.developer.checkpoint.CheckpointEntity;
import ru.allstreets.developer.checkpoint.CheckpointService;
import ru.allstreets.developer.config.AgentGraphRunner;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Recovery-возобновление идёт через общий executor и уведомляет об исходе —
 * нет чекпоинта/перегрузка не «висят» молча.
 */
class TaskLauncherResumeAfterRestartTest {

    private static final String TASK_ID = "22222222-2222-2222-2222-222222222222";
    private static final long CHAT_ID = 42L;

    private AgentGraphRunner graphRunner;
    private TelegramGateway telegram;
    private ActiveTaskRegistry taskRegistry;
    private CheckpointService checkpointService;
    private ThreadPoolExecutor executor;
    private TaskLauncher launcher;

    @BeforeEach
    void setUp() {
        graphRunner = mock(AgentGraphRunner.class);
        telegram = mock(TelegramGateway.class);
        taskRegistry = mock(ActiveTaskRegistry.class);
        checkpointService = mock(CheckpointService.class);
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        launcher = new TaskLauncher(graphRunner, telegram, taskRegistry,
                mock(HumanInputRegistry.class), checkpointService, mock(OpenCodeSessionPool.class),
                executor, mock(ChatClient.class), mock(PriorTaskContextBuilder.class),
                new ru.allstreets.developer.metrics.TaskMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void noCheckpoint_returnsFalse_andNotifies() {
        when(checkpointService.getLatestCheckpoint(TASK_ID)).thenReturn(null);

        assertFalse(launcher.resumeAfterRestart(TASK_ID, CHAT_ID));

        verify(telegram).sendMessage(eq(CHAT_ID), contains("checkpoint не найден"), eq(TASK_ID));
        verify(graphRunner, never()).resume(anyString(), any(Message[].class));
    }

    @Test
    void withCheckpoint_submitsResume_andNotifiesCompletion() {
        when(checkpointService.getLatestCheckpoint(TASK_ID))
                .thenReturn(new CheckpointEntity("cp-1", TASK_ID, "analyst", "{}", "RUNNING"));
        when(graphRunner.resume(eq(TASK_ID), any(Message[].class)))
                .thenReturn(AgentResult.ofText("ok"));

        assertTrue(launcher.resumeAfterRestart(TASK_ID, CHAT_ID));

        verify(telegram).sendMessage(eq(CHAT_ID), contains("Приложение перезапущено"), eq(TASK_ID));
        verify(telegram, timeout(2000)).sendMessage(eq(CHAT_ID), contains("завершена"), eq(TASK_ID));
        verify(taskRegistry).markCompleted(TASK_ID);
    }
}
