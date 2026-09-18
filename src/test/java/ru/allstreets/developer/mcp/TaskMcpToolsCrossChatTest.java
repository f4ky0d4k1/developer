package ru.allstreets.developer.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import ru.allstreets.developer.PostgresTestBase;
import ru.allstreets.developer.checkpoint.ChatMessageRepository;
import ru.allstreets.developer.checkpoint.CheckpointRepository;
import ru.allstreets.developer.checkpoint.TaskChatRepository;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskLockService;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.opencode.TaskProgressRegistry;
import ru.allstreets.developer.telegram.ActiveTaskRegistry;
import ru.allstreets.developer.telegram.ChatTitleResolver;
import ru.allstreets.developer.telegram.TaskLauncher;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Управление задачей из другого чата (BACKEND-441).
 * <p>
 * Контракт: {@code closeTask}/{@code cancelTask}/{@code restartTask}/{@code resetSlot} получают
 * {@link ToolContext} с {@code chatId} вызывающего. Если задача принадлежит текущему чату —
 * прежнее поведение без ограничений. Если задача из другого чата — операция разрешена только
 * trigger-user, иначе отказ со словами {@code not allowed} и без побочных эффектов.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TaskMcpToolsCrossChatTest extends PostgresTestBase {

    private static final String TRIGGER = "dima";
    /** Задача живёт в чате 2. */
    private static final long OWNER_CHAT = 2L;
    /** Вызывающий из другого чата. */
    private static final long FOREIGN_CHAT = 1L;
    private static final String TASK = "aaaa0001-0000-0000-0000-000000000000";

    @Autowired
    private TaskRepository taskRepo;
    @Autowired
    private TaskChatRepository taskChatRepo;

    private TaskLauncher taskLauncher;
    private TaskMcpTools tools;

    @BeforeEach
    void setUp() {
        taskLauncher = mock(TaskLauncher.class);
        var registry = new ActiveTaskRegistry(taskRepo, taskChatRepo);
        tools = new TaskMcpTools(
                registry,
                taskRepo,
                mock(CheckpointRepository.class),
                mock(TaskLockService.class),
                taskLauncher,
                mock(HumanInputRegistry.class),
                mock(TaskProgressRegistry.class),
                mock(ChatMessageRepository.class),
                mock(ChatTitleResolver.class),
                TRIGGER);
        taskRepo.saveAndFlush(new TaskEntity(TASK, "RUNNING", "desc", "title", OWNER_CHAT));
    }

    private static ToolContext ctx(String username, long chatId) {
        Map<String, Object> context = new HashMap<>();
        context.put("username", username);
        context.put("chatId", chatId);
        return new ToolContext(context);
    }

    // ---- closeTask ----

    @Test
    void closeTask_ownChat_nonTriggerUser_allowed() {
        when(taskLauncher.close(TASK, OWNER_CHAT)).thenReturn(true);

        String out = tools.closeTask(TASK, ctx("stranger", OWNER_CHAT));

        assertTrue(out.contains("closed"), out);
        verify(taskLauncher).close(TASK, OWNER_CHAT);
    }

    @Test
    void closeTask_otherChat_nonTriggerUser_denied() {
        String out = tools.closeTask(TASK, ctx("stranger", FOREIGN_CHAT));

        assertTrue(out.contains("not allowed"), out);
        verify(taskLauncher, never()).close(anyString(), anyLong());
    }

    @Test
    void closeTask_otherChat_triggerUser_allowed() {
        when(taskLauncher.close(TASK, OWNER_CHAT)).thenReturn(true);

        tools.closeTask(TASK, ctx(TRIGGER, FOREIGN_CHAT));

        verify(taskLauncher).close(TASK, OWNER_CHAT);
    }

    // ---- cancelTask ----

    @Test
    void cancelTask_ownChat_nonTriggerUser_allowed() {
        tools.cancelTask(TASK, ctx("stranger", OWNER_CHAT));

        verify(taskLauncher).cancel(TASK);
    }

    @Test
    void cancelTask_otherChat_nonTriggerUser_denied() {
        String out = tools.cancelTask(TASK, ctx("stranger", FOREIGN_CHAT));

        assertTrue(out.contains("not allowed"), out);
        verify(taskLauncher, never()).cancel(anyString());
        verify(taskLauncher, never()).releaseSlot(anyString());
    }

    @Test
    void cancelTask_otherChat_triggerUser_allowed() {
        tools.cancelTask(TASK, ctx(TRIGGER, FOREIGN_CHAT));

        verify(taskLauncher).cancel(TASK);
    }

    // ---- resetSlot ----

    @Test
    void resetSlot_otherChat_nonTriggerUser_denied() {
        String out = tools.resetSlot(TASK, ctx("stranger", FOREIGN_CHAT));

        assertTrue(out.contains("not allowed"), out);
        verify(taskLauncher, never()).resetSlot(anyString());
    }

    @Test
    void resetSlot_otherChat_triggerUser_allowed() {
        when(taskLauncher.resetSlot(TASK)).thenReturn(true);

        tools.resetSlot(TASK, ctx(TRIGGER, FOREIGN_CHAT));

        verify(taskLauncher).resetSlot(TASK);
    }

    // ---- restartTask ----

    @Test
    void restartTask_otherChat_nonTriggerUser_denied() {
        String out = tools.restartTask(TASK, "доп. контекст", ctx("stranger", FOREIGN_CHAT));

        assertTrue(out.contains("not allowed"), out);
        verify(taskLauncher, never()).restart(anyString(), anyLong(), any());
        verify(taskLauncher, never()).rework(anyString(), anyLong(), any());
    }

    @Test
    void restartTask_otherChat_triggerUser_allowed() {
        when(taskLauncher.restart(TASK, OWNER_CHAT, "доп. контекст")).thenReturn(true);

        tools.restartTask(TASK, "доп. контекст", ctx(TRIGGER, FOREIGN_CHAT));

        verify(taskLauncher).restart(TASK, OWNER_CHAT, "доп. контекст");
    }
}
