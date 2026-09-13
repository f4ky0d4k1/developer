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

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Per-chat память «проект ↔ задачи»: {@link TaskMcpTools#getChatProjects} отдаёт
 * классификатору список репозиториев чата с числом задач, чтобы он мог определить
 * целевой репозиторий новой задачи (или уточнить у пользователя).
 * <p>
 * Агрегация — на стороне БД с LIMIT, поэтому на большом чате (тысячи задач,
 * десятки проектов) вывод и объём выборки ограничены.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TaskMcpToolsChatProjectsTest extends PostgresTestBase {

    @Autowired
    private TaskRepository taskRepo;

    private TaskMcpTools tools() {
        return new TaskMcpTools(
                mock(ActiveTaskRegistry.class),
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

    private void task(String taskId, long chatId, String repo, String title) {
        TaskEntity t = new TaskEntity(taskId, "RUNNING", "desc " + taskId, title, chatId);
        t.setRepo(repo);
        taskRepo.saveAndFlush(t);
    }

    @Test
    void getChatProjects_groupsReposPerChat() {
        task("t1", 1L, "allstreets/backend", "Бэкенд фича");
        task("t2", 1L, "allstreets/backend", "Ещё бэкенд");
        task("t3", 1L, "allstreets/frontend", "Фронт фича");
        task("t4", 2L, "other/repo", "Другой чат");

        String result = tools().getChatProjects(1L);

        assertTrue(result.contains("allstreets/backend (2 task(s)"), result);
        assertTrue(result.contains("allstreets/frontend (1 task(s)"), result);
        assertFalse(result.contains("other/repo"), "задачи другого чата не должны попадать: " + result);
    }

    @Test
    void getChatProjects_ignoresTasksWithoutRepo() {
        task("t1", 5L, null, "Задача без репо");
        task("t2", 5L, "allstreets/backend", "С репо");

        String result = tools().getChatProjects(5L);

        assertTrue(result.contains("allstreets/backend (1 task(s)"), result);
    }

    @Test
    void getChatProjects_unknownChat_asksToClarify() {
        String result = tools().getChatProjects(999L);

        assertTrue(result.contains("No projects recorded"), result);
        assertTrue(result.contains("Ask the user"), result);
    }

    @Test
    void getChatProjects_groupsReposCaseInsensitively() {
        task("t1", 7L, "AllStreets/Backend", "A");
        task("t2", 7L, "  allstreets/backend ", "B");

        String result = tools().getChatProjects(7L);

        assertTrue(result.contains("allstreets/backend (2 task(s)"), result);
    }

    @Test
    void getChatProjects_capsOutputToMaxProjects() {
        for (int i = 0; i < 12; i++) {
            task("t" + i, 8L, "allstreets/repo" + i, "task " + i);
        }

        String result = tools().getChatProjects(8L);

        long projectLines = result.lines().filter(l -> l.startsWith("- ")).count();
        assertEquals(10, projectLines, "ровно top-10 проектов: " + result);
        assertTrue(result.contains("(+more projects)"), "должно быть указано, что есть ещё: " + result);
    }

    @Test
    void getChatProjects_largeChat_isBoundedAndCorrect() {
        // 50 проектов × 40 задач = 2000 задач в одном чате — «большой чат».
        var tasks = new ArrayList<TaskEntity>();
        for (int p = 0; p < 50; p++) {
            for (int i = 0; i < 40; i++) {
                TaskEntity t = new TaskEntity("big-" + p + "-" + i, "RUNNING", "desc", "title", 42L);
                t.setRepo("allstreets/repo" + p);
                tasks.add(t);
            }
        }
        taskRepo.saveAll(tasks);
        taskRepo.flush();

        String result = tools().getChatProjects(42L);

        long projectLines = result.lines().filter(l -> l.startsWith("- ")).count();
        assertEquals(10, projectLines, "вывод ограничен top-10 даже на 2000 задачах: " + result);
        assertTrue(result.contains("(+more projects)"), result);
        // Счётчик задач агрегируется по всем задачам проекта, а не по выборке.
        assertTrue(result.contains("(40 task(s)"), "счётчик задач проекта должен быть 40: " + result);
    }
}
