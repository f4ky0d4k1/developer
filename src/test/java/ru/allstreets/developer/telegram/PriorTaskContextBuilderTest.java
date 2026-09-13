package ru.allstreets.developer.telegram;

import io.github.asekka.springai.agents.core.AgentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.checkpoint.CheckpointService;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.state.TaskState;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Перенос контекста прошлой задачи при ретрае через НОВУЮ задачу: описание, Tracker-issue
 * (из state чекпоинта) и недавняя переписка чата — чтобы аналитик не начинал с нуля и не
 * создавал дубль Tracker-задачи.
 */
class PriorTaskContextBuilderTest {

    private static final String PRIOR_ID = "aaaaaaaa-1111-1111-1111-111111111111";

    private TaskRepository taskRepo;
    private CheckpointService checkpointService;
    private ChatMemoryService chatMemory;
    private PriorTaskContextBuilder builder;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        checkpointService = mock(CheckpointService.class);
        chatMemory = mock(ChatMemoryService.class);
        builder = new PriorTaskContextBuilder(taskRepo, checkpointService, chatMemory);
    }

    @Test
    void noPriorTaskId_returnsEmpty() {
        assertEquals("", builder.build(null));
        assertEquals("", builder.build("   "));
    }

    @Test
    void assemblesDescriptionTrackerAndHistory() {
        TaskEntity task = new TaskEntity(PRIOR_ID, "FAILED", "вынести тестовых пользователей", "test_users", 7L);
        when(taskRepo.findById(PRIOR_ID)).thenReturn(Optional.of(task));
        when(checkpointService.restoreCheckpoint(PRIOR_ID))
                .thenReturn(AgentContext.of("x").with(TaskState.TRACKER_ISSUE, "BACKEND-432"));
        when(chatMemory.getHistoryText(7L)).thenReturn("user: перезапусти\nbot: ок\n");

        String ctx = builder.build(PRIOR_ID);

        assertTrue(ctx.contains("aaaaaaaa"), ctx);
        assertTrue(ctx.contains("FAILED"), ctx);
        assertTrue(ctx.contains("test_users"), ctx);
        assertTrue(ctx.contains("BACKEND-432"), ctx);
        assertTrue(ctx.contains("НЕ создавай новую"), "должен явно запрещать дубль Tracker: " + ctx);
        assertTrue(ctx.contains("перезапусти"), "должна быть переписка чата: " + ctx);
    }
}
