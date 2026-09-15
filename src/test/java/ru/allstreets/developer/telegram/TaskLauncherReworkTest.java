package ru.allstreets.developer.telegram;

import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import ru.allstreets.developer.checkpoint.CheckpointService;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.config.AgentGraphRunner;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.state.TaskState;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Реворк/закрытие задачи: та же задача (тот же taskId) возвращается в работу с аналитика
 * с новыми вводными; закрытие переводит в CLOSED и (только тогда) освобождает слот.
 */
class TaskLauncherReworkTest {

    private static final String TASK_ID = "ec0a2004-1111-2222-3333-444455556666";
    private static final long CHAT_ID = 1L;

    private AgentGraphRunner graphRunner;
    private ActiveTaskRegistry taskRegistry;
    private TaskRepository taskRepo;
    private OpenCodeSessionPool sessionPool;
    private ThreadPoolExecutor executor;
    private TaskLauncher launcher;

    @BeforeEach
    void setUp() {
        graphRunner = mock(AgentGraphRunner.class);
        taskRegistry = mock(ActiveTaskRegistry.class);
        taskRepo = mock(TaskRepository.class);
        sessionPool = mock(OpenCodeSessionPool.class);
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        launcher = new TaskLauncher(graphRunner, mock(TelegramGateway.class), taskRegistry,
                mock(HumanInputRegistry.class), mock(CheckpointService.class), sessionPool,
                executor, mock(ChatClient.class), mock(PriorTaskContextBuilder.class),
                new ru.allstreets.developer.metrics.TaskMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                taskRepo, mock(ru.allstreets.developer.checkpoint.TaskLockService.class));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static String taskText(AgentContext ctx) {
        List<org.springframework.ai.chat.messages.Message> messages = ctx.messages();
        return messages.isEmpty() ? null : messages.getLast().getText();
    }

    @Test
    void unknownTask_returnsFalse() {
        when(taskRepo.findById(TASK_ID)).thenReturn(Optional.empty());

        assertFalse(launcher.rework(TASK_ID, CHAT_ID, "замечание"));
        verifyNoInteractions(graphRunner);
    }

    @Test
    void rework_rerunsSameTaskWithMergedInput() {
        when(taskRepo.findById(TASK_ID))
                .thenReturn(Optional.of(new TaskEntity(TASK_ID, "COMPLETED", "исходное описание", "Техучётки", CHAT_ID)));
        when(graphRunner.run(any(AgentContext.class))).thenReturn(AgentResult.ofText("ok"));

        assertTrue(launcher.rework(TASK_ID, CHAT_ID, "замечание из PR"));

        verify(taskRegistry).markRunning(TASK_ID);
        ArgumentCaptor<AgentContext> ctxCaptor = ArgumentCaptor.forClass(AgentContext.class);
        verify(graphRunner, timeout(2000)).run(ctxCaptor.capture());
        AgentContext ctx = ctxCaptor.getValue();
        assertEquals(TASK_ID, ctx.get(TaskState.TASK_ID), "реворк идёт под тем же taskId — новых задач не создаём");
        String text = taskText(ctx);
        assertNotNull(text);
        assertTrue(text.contains("исходное описание"));
        assertTrue(text.contains("замечание из PR"), "вводные должны попасть в описание задачи");
    }

    @Test
    void close_marksClosedAndFreesSlot() {
        when(taskRepo.findById(TASK_ID))
                .thenReturn(Optional.of(new TaskEntity(TASK_ID, "COMPLETED", "описание", "Техучётки", CHAT_ID)));

        assertTrue(launcher.close(TASK_ID, CHAT_ID));

        verify(taskRegistry).markClosed(TASK_ID);
        verify(sessionPool).releaseForTask(TASK_ID);
    }

    @Test
    void close_unknownTask_returnsFalse() {
        when(taskRepo.findById(TASK_ID)).thenReturn(Optional.empty());

        assertFalse(launcher.close(TASK_ID, CHAT_ID));
        verify(sessionPool, org.mockito.Mockito.never()).releaseForTask(TASK_ID);
    }
}
