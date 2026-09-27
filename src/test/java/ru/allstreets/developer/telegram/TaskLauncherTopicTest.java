package ru.allstreets.developer.telegram;

import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Проводка жизненного цикла forum-темы в {@link TaskLauncher} (BACKEND-443, T3/T6/T7).
 * <p>
 * Тема создаётся ДО стартового сообщения (AC «нет утечки в General»), затем задача
 * привязывается topic-scoped anchor'ом и описание закрепляется. При недоступности
 * Telegram/БД задача всё равно запускается — сообщение уходит в общий чат (fail-open),
 * без pin и без topic-scoped anchor. Закрытие задачи закрывает forum-тему.
 */
class TaskLauncherTopicTest {

    private static final long CHAT_ID = 7L;
    private static final String TASK_ID = "ec0a2004-1111-2222-3333-444455556666";
    private static final long THREAD_ID = 555L;

    private AgentGraphRunner graphRunner;
    private TelegramGateway telegram;
    private TelegramTopicService topicService;
    private ReplyAnchorRegistry replyAnchors;
    private TaskRepository taskRepo;
    private ThreadPoolExecutor executor;
    private TaskLauncher launcher;

    @BeforeEach
    void setUp() {
        graphRunner = mock(AgentGraphRunner.class);
        telegram = mock(TelegramGateway.class);
        topicService = mock(TelegramTopicService.class);
        replyAnchors = mock(ReplyAnchorRegistry.class);
        taskRepo = mock(TaskRepository.class);
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        launcher = new TaskLauncher(graphRunner, telegram,
                mock(ActiveTaskRegistry.class), mock(HumanInputRegistry.class), mock(CheckpointService.class),
                mock(OpenCodeSessionPool.class), executor, mock(ChatClient.class), mock(PriorTaskContextBuilder.class),
                new ru.allstreets.developer.metrics.TaskMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                taskRepo, mock(TaskLockService.class), replyAnchors);
        // topicService — field-injection (@Autowired required=false), как в проде.
        ReflectionTestUtils.setField(launcher, "topicService", topicService);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void launch_createsTopic_thenPinsAndAnchorsBeforeFirstMessage() {
        when(graphRunner.run(any(AgentContext.class))).thenReturn(AgentResult.ofText("ok"));
        when(topicService.ensureTopic(anyString(), eq(CHAT_ID), anyString())).thenReturn(THREAD_ID);

        launcher.launch("Сделай фичу", CHAT_ID, "owner/repo", null);

        ArgumentCaptor<String> taskIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(topicService).ensureTopic(taskIdCaptor.capture(), eq(CHAT_ID), anyString());
        String taskId = taskIdCaptor.getValue();
        assertNotNull(taskId, "тема создаётся для сгенерированного taskId");

        verify(topicService).pinTopicDescription(eq(taskId), anyString());
        verify(replyAnchors).anchorTask(eq(taskId), eq(CHAT_ID), eq(THREAD_ID));
        verify(telegram, atLeastOnce()).sendMessage(eq(CHAT_ID), anyString(), eq(taskId));
    }

    @Test
    void launch_topicUnavailable_fallsBackToGeneral_withoutPinOrTopicAnchor() {
        when(graphRunner.run(any(AgentContext.class))).thenReturn(AgentResult.ofText("ok"));
        when(topicService.ensureTopic(anyString(), eq(CHAT_ID), anyString())).thenReturn(null);

        launcher.launch("Сделай фичу", CHAT_ID, "owner/repo", null);

        verify(topicService, never()).pinTopicDescription(anyString(), anyString());
        verify(replyAnchors, never()).anchorTask(anyString(), anyLong(), anyLong());
        // задача всё равно стартует: сообщение уходит (в General через gateway-резолвер)
        verify(telegram, atLeastOnce()).sendMessage(eq(CHAT_ID), anyString(), anyString());
    }

    @Test
    void launch_topicServiceThrows_failOpen_launchStillProceeds() {
        when(graphRunner.run(any(AgentContext.class))).thenReturn(AgentResult.ofText("ok"));
        when(topicService.ensureTopic(anyString(), eq(CHAT_ID), anyString()))
                .thenThrow(new RuntimeException("Forbidden: not enough rights to manage topics"));

        launcher.launch("Сделай фичу", CHAT_ID, "owner/repo", null);

        verify(topicService, never()).pinTopicDescription(anyString(), anyString());
        verify(telegram, atLeastOnce()).sendMessage(eq(CHAT_ID), anyString(), anyString());
    }

    @Test
    void close_closesForumTopic() {
        when(taskRepo.findById(TASK_ID))
                .thenReturn(Optional.of(new TaskEntity(TASK_ID, "COMPLETED", "d", "Техучётки", CHAT_ID)));

        launcher.close(TASK_ID, CHAT_ID);

        verify(topicService).closeTopic(TASK_ID);
    }
}
