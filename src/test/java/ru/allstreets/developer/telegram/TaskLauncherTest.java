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
import ru.allstreets.developer.checkpoint.TaskLockService;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.config.AgentGraphRunner;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;

import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Нормализация целевого репозитория — дедупликация per-chat памяти проектов.
 * <p>
 * Плюс reply-anchor lifecycle: {@code launch} привязывает новую задачу к
 * сообщению-источнику ({@code anchorTask}), {@code close}/{@code cancel}
 * очищают anchor ({@code forgetTask}), чтобы реестр не тёк.
 */
class TaskLauncherTest {

    private static final String TASK_ID = "ec0a2004-1111-2222-3333-444455556666";
    private static final long CHAT_ID = 7L;

    private AgentGraphRunner graphRunner;
    private TaskRepository taskRepo;
    private ReplyAnchorRegistry replyAnchors;
    private ThreadPoolExecutor executor;
    private TaskLauncher launcher;

    @BeforeEach
    void setUp() {
        graphRunner = mock(AgentGraphRunner.class);
        taskRepo = mock(TaskRepository.class);
        replyAnchors = mock(ReplyAnchorRegistry.class);
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        launcher = new TaskLauncher(graphRunner, mock(TelegramGateway.class),
                mock(ActiveTaskRegistry.class), mock(HumanInputRegistry.class), mock(CheckpointService.class),
                mock(OpenCodeSessionPool.class), executor, mock(ChatClient.class), mock(PriorTaskContextBuilder.class),
                new ru.allstreets.developer.metrics.TaskMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                taskRepo, mock(TaskLockService.class), replyAnchors);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void normalizeRepo_trimsAndLowercases() {
        assertEquals("allstreets/backend", TaskLauncher.normalizeRepo("  AllStreets/Backend "));
        assertEquals("allstreets/backend", TaskLauncher.normalizeRepo("allstreets/backend"));
    }

    @Test
    void normalizeRepo_blankIsNull() {
        assertNull(TaskLauncher.normalizeRepo(null));
        assertNull(TaskLauncher.normalizeRepo(""));
        assertNull(TaskLauncher.normalizeRepo("   "));
    }

    @Test
    void launch_anchorsNewTaskToChat() {
        when(graphRunner.run(any(AgentContext.class))).thenReturn(AgentResult.ofText("ok"));

        launcher.launch("Сделай фичу", CHAT_ID, "owner/repo", null);

        ArgumentCaptor<String> taskIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(replyAnchors).anchorTask(taskIdCaptor.capture(), eq(CHAT_ID));
        String taskId = taskIdCaptor.getValue();
        assertNotNull(taskId, "anchor привязывается к сгенерированному taskId");
        assertTrue(taskId.length() >= 8, "taskId должен быть UUID, а не обрезком");
    }

    @Test
    void close_forgetsAnchor() {
        when(taskRepo.findById(TASK_ID))
                .thenReturn(Optional.of(new TaskEntity(TASK_ID, "COMPLETED", "описание", "Техучётки", CHAT_ID)));

        assertTrue(launcher.close(TASK_ID, CHAT_ID));

        verify(replyAnchors, atLeastOnce()).forgetTask(TASK_ID);
    }

    @Test
    void cancel_forgetsAnchor() {
        launcher.cancel(TASK_ID);

        verify(replyAnchors, atLeastOnce()).forgetTask(TASK_ID);
    }
}
