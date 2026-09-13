package ru.allstreets.developer.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import ru.allstreets.developer.PostgresTestBase;
import ru.allstreets.developer.checkpoint.*;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.opencode.TaskProgressRegistry;
import ru.allstreets.developer.telegram.ActiveTaskRegistry;
import ru.allstreets.developer.telegram.TaskLauncher;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Пагинация задач чата: {@code getChatTasks} отдаёт страницы (свежие первыми) с репо,
 * а {@code ActiveTaskRegistry.getChatTasksPage} ограничивает выборку — контекст
 * классификатора не растёт с числом задач в чате.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TaskMcpToolsTasksTest extends PostgresTestBase {

    @Autowired
    private TaskRepository taskRepo;

    private TaskMcpTools tools() {
        var registry = new ActiveTaskRegistry(taskRepo, mock(TaskChatRepository.class));
        return new TaskMcpTools(
                registry,
                taskRepo,
                mock(CheckpointRepository.class),
                mock(CheckpointService.class),
                mock(TaskLockService.class),
                mock(TaskLauncher.class),
                mock(HumanInputRegistry.class),
                mock(TaskProgressRegistry.class),
                mock(ChatMessageRepository.class),
                "");
    }

    @Test
    void getChatTasks_paginatesNewestFirst_withRepo() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 15; i++) {
            TaskEntity t = new TaskEntity("t" + i, "RUNNING", "desc", "title " + i, 10L);
            t.setRepo("allstreets/repo" + (i % 3));
            t.setCreatedAt(base.plusSeconds(i));
            taskRepo.save(t);
        }
        taskRepo.flush();

        var tools = tools();

        String page0 = tools.getChatTasks(10L, 0, 10);
        assertTrue(page0.contains("t14"), "свежие первыми: " + page0);
        assertTrue(page0.contains("repo=allstreets/repo"), "репо в выдаче: " + page0);
        assertTrue(page0.contains("page 0 of 2 (total 15 tasks)"), page0);
        assertFalse(page0.contains("t4\n"), "t4 не на первой странице: " + page0);

        String page1 = tools.getChatTasks(10L, 1, 10);
        assertTrue(page1.contains("t4"), page1);
        assertTrue(page1.contains("page 1 of 2 (total 15 tasks)"), page1);
        assertFalse(page1.contains("t14"), "t14 не на второй странице: " + page1);
    }

    @Test
    void getChatTasks_emptyPage() {
        String result = tools().getChatTasks(123L, 5, 10);
        assertTrue(result.contains("No tasks on page 5"), result);
    }
}
