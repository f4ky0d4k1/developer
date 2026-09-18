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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Режим {@code allChats=true} инструментов {@link TaskMcpTools} (BACKEND-441).
 * <p>
 * Контракт (реализация должна ему соответствовать):
 * <ul>
 *   <li>режим доступен ТОЛЬКО trigger-user ({@code username} из ToolContext, deny-by-default);
 *       не-trigger-user получает отказ со словами {@code not allowed};</li>
 *   <li>{@code getChatTasks} по всем не удалённым задачам, свежие первыми, сгруппировано по чатам:
 *       заголовок чата + {@code (chatId: N)}, ниже строки
 *       {@code taskId8 | status | repo=... | title | created_at}; в конце
 *       {@code page X of Y (total N tasks)};</li>
 *   <li>фильтры {@code status}/{@code repo} (без учёта регистра)/{@code from}/{@code to}
 *       применяются ДО пагинации (totalElements и страницы корректны);</li>
 *   <li>некорректные {@code from}/{@code to} — ответ с {@code ERROR}, без исключения;</li>
 *   <li>{@code getChatProjects} — уникальные репозитории (LOWER/TRIM) всех чатов со счётчиком,
 *       максимум 10 + пометка {@code (+more projects)};</li>
 *   <li>{@code getLastTaskForChat} — последняя по createdAt не удалённая задача среди всех чатов,
 *       в выводе есть {@code chat_id: N}.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TaskMcpToolsAllChatsTest extends PostgresTestBase {

    private static final String TRIGGER = "dima";

    @Autowired
    private TaskRepository taskRepo;
    @Autowired
    private TaskChatRepository taskChatRepo;

    private ChatTitleResolver titles;
    private TaskMcpTools tools;

    @BeforeEach
    void setUp() {
        titles = mock(ChatTitleResolver.class);
        var registry = new ActiveTaskRegistry(taskRepo, taskChatRepo);
        tools = new TaskMcpTools(
                registry,
                taskRepo,
                mock(CheckpointRepository.class),
                mock(TaskLockService.class),
                mock(TaskLauncher.class),
                mock(HumanInputRegistry.class),
                mock(TaskProgressRegistry.class),
                mock(ChatMessageRepository.class),
                titles,
                TRIGGER);
    }

    private static ToolContext ctx(String username, long chatId) {
        Map<String, Object> context = new HashMap<>();
        context.put("username", username);
        context.put("chatId", chatId);
        return new ToolContext(context);
    }

    private TaskEntity task(String shortId, long chatId, String status, String repo,
                            String title, Instant createdAt) {
        TaskEntity t = new TaskEntity(shortId + "-0000-0000-0000-000000000000",
                status, "desc " + shortId, title, chatId);
        t.setRepo(repo);
        t.setCreatedAt(createdAt);
        return taskRepo.saveAndFlush(t);
    }

    // ------------------------------------------------------------------
    // getChatTasks — allChats
    // ------------------------------------------------------------------

    @Test
    void getChatTasks_allChats_triggerUser_groupsByChatWithTitlesAndChatIds() {
        Instant t10 = Instant.parse("2026-01-01T00:00:10Z");
        Instant t20 = Instant.parse("2026-01-01T00:00:20Z");
        Instant t30 = Instant.parse("2026-01-01T00:00:30Z");
        task("aaaa0001", 1L, "RUNNING", "allstreets/backend", "Alpha", t10);
        task("bbbb0002", 2L, "FAILED", "other/frontend", "Beta", t20);
        task("cccc0003", 1L, "COMPLETED", "allstreets/backend", "Gamma", t30);
        when(titles.titleOrFallback(1L)).thenReturn("Chat One");
        when(titles.titleOrFallback(2L)).thenReturn("Chat Two");

        String out = tools.getChatTasks(999L, 0, 10, true, null, null, null, null, ctx(TRIGGER, 999L));

        assertTrue(out.contains("Chat One (chatId: 1)"), out);
        assertTrue(out.contains("Chat Two (chatId: 2)"), out);
        assertTrue(out.contains("cccc0003 | COMPLETED | repo=allstreets/backend | Gamma | " + t30), out);
        assertTrue(out.contains("aaaa0001 | RUNNING | repo=allstreets/backend | Alpha | " + t10), out);
        assertTrue(out.contains("bbbb0002 | FAILED | repo=other/frontend | Beta | " + t20), out);
        assertTrue(out.contains("page 0 of 1 (total 3 tasks)"), out);

        int chatOne = out.indexOf("Chat One (chatId: 1)");
        int chatTwo = out.indexOf("Chat Two (chatId: 2)");
        int newest = out.indexOf("cccc0003");
        int oldestInChatOne = out.indexOf("aaaa0001");
        int chatTwoTask = out.indexOf("bbbb0002");
        assertTrue(chatOne >= 0 && chatOne < newest && newest < oldestInChatOne
                        && oldestInChatOne < chatTwo && chatTwo < chatTwoTask,
                "задачи должны быть сгруппированы по чатам, свежие первыми: " + out);
    }

    @Test
    void getChatTasks_allChats_nonTriggerUser_isDenied() {
        task("aaaa0001", 1L, "RUNNING", "allstreets/backend", "Alpha", Instant.parse("2026-01-01T00:00:10Z"));

        String out = tools.getChatTasks(999L, 0, 10, true, null, null, null, null, ctx("stranger", 1L));

        assertTrue(out.contains("not allowed"), out);
        assertFalse(out.contains("aaaa0001"), "при отказе данные не должны утекать: " + out);
    }

    @Test
    void getChatTasks_allChatsFalse_keepsPerChatBehaviour() {
        task("aaaa0001", 1L, "RUNNING", "allstreets/backend", "Alpha", Instant.parse("2026-01-01T00:00:10Z"));
        task("bbbb0002", 2L, "FAILED", "other/frontend", "Beta", Instant.parse("2026-01-01T00:00:20Z"));

        String out = tools.getChatTasks(1L, 0, 10, false, null, null, null, null, ctx(TRIGGER, 1L));

        assertTrue(out.contains("aaaa0001"), out);
        assertFalse(out.contains("bbbb0002"), "allChats=false не должен отдавать чужие чаты: " + out);
    }

    @Test
    void getChatTasks_nullAllChats_keepsPerChatBehaviour() {
        task("aaaa0001", 1L, "RUNNING", "allstreets/backend", "Alpha", Instant.parse("2026-01-01T00:00:10Z"));
        task("bbbb0002", 2L, "FAILED", "other/frontend", "Beta", Instant.parse("2026-01-01T00:00:20Z"));

        String out = tools.getChatTasks(1L, 0, 10, null, null, null, null, null, ctx(TRIGGER, 1L));

        assertTrue(out.contains("aaaa0001"), out);
        assertFalse(out.contains("bbbb0002"), out);
    }

    @Test
    void getChatTasks_allChats_statusFilterAppliesBeforePagination() {
        // 20 FAILED + 5 RUNNING. Если фильтровать после пагинации, total будет неверным.
        for (int i = 0; i < 20; i++) {
            task(String.format("f%07d", i), i % 2, "FAILED", "allstreets/backend",
                    "Failed " + i, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i));
        }
        for (int i = 0; i < 5; i++) {
            task(String.format("r%07d", i), 1L, "RUNNING", "allstreets/backend",
                    "Running " + i, Instant.parse("2026-01-01T01:00:00Z").plusSeconds(i));
        }

        String out = tools.getChatTasks(999L, 0, 8, true, "FAILED", null, null, null, ctx(TRIGGER, 999L));

        assertTrue(out.contains("page 0 of 3 (total 20 tasks)"), out);
        assertTrue(out.contains("FAILED"), out);
        assertFalse(out.contains("RUNNING"), "фильтр status должен применяться в БД: " + out);
    }

    @Test
    void getChatTasks_allChats_repoFilterIsCaseInsensitive() {
        task("aaaa0001", 1L, "RUNNING", "allstreets/backend", "Match", Instant.parse("2026-01-01T00:00:10Z"));
        task("bbbb0002", 2L, "RUNNING", "other/frontend", "Other", Instant.parse("2026-01-01T00:00:20Z"));

        String out = tools.getChatTasks(999L, 0, 10, true, null, "AllStreets/Backend", null, null, ctx(TRIGGER, 999L));

        assertTrue(out.contains("aaaa0001"), out);
        assertFalse(out.contains("bbbb0002"), "repo-фильтр без учёта регистра: " + out);
    }

    @Test
    void getChatTasks_allChats_dateRangeFilter() {
        task("aaaa0001", 1L, "RUNNING", "allstreets/backend", "Before", Instant.parse("2026-01-01T00:00:10Z"));
        task("bbbb0002", 2L, "RUNNING", "allstreets/backend", "Inside", Instant.parse("2026-01-01T00:00:20Z"));
        task("cccc0003", 1L, "RUNNING", "allstreets/backend", "After", Instant.parse("2026-01-01T00:00:30Z"));

        String out = tools.getChatTasks(999L, 0, 10, true, null, null,
                "2026-01-01T00:00:15Z", "2026-01-01T00:00:25Z", ctx(TRIGGER, 999L));

        assertTrue(out.contains("bbbb0002"), out);
        assertFalse(out.contains("aaaa0001"), "задача до from не должна попадать: " + out);
        assertFalse(out.contains("cccc0003"), "задача после to не должна попадать: " + out);
        assertTrue(out.contains("(total 1 tasks)"), out);
    }

    @Test
    void getChatTasks_allChats_invalidFrom_returnsError() {
        String out = tools.getChatTasks(999L, 0, 10, true, null, null, "not-a-date", null, ctx(TRIGGER, 999L));

        assertTrue(out.contains("ERROR"), "некорректный from → понятная ошибка, а не падение: " + out);
    }

    @Test
    void getChatTasks_allChats_invalidTo_returnsError() {
        String out = tools.getChatTasks(999L, 0, 10, true, null, null, null, "2026-13-45T99:99:99Z", ctx(TRIGGER, 999L));

        assertTrue(out.contains("ERROR"), "некорректный to → понятная ошибка, а не падение: " + out);
    }

    // ------------------------------------------------------------------
    // getChatProjects — allChats
    // ------------------------------------------------------------------

    @Test
    void getChatProjects_allChats_aggregatesReposAcrossChatsCaseInsensitively() {
        task("aaaa0001", 1L, "RUNNING", "allstreets/backend", "A", Instant.parse("2026-01-01T00:00:10Z"));
        task("bbbb0002", 2L, "RUNNING", "AllStreets/Backend", "B", Instant.parse("2026-01-01T00:00:20Z"));
        task("cccc0003", 3L, "RUNNING", "other/frontend", "C", Instant.parse("2026-01-01T00:00:30Z"));

        String out = tools.getChatProjects(999L, true, ctx(TRIGGER, 999L));

        assertTrue(out.contains("allstreets/backend (2 task(s)"), out);
        assertTrue(out.contains("other/frontend (1 task(s)"), out);
    }

    @Test
    void getChatProjects_allChats_capsOutputToMaxProjects() {
        for (int i = 0; i < 12; i++) {
            task(String.format("p%07d", i), i, "RUNNING", "allstreets/repo" + i,
                    "task " + i, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i));
        }

        String out = tools.getChatProjects(999L, true, ctx(TRIGGER, 999L));

        long projectLines = out.lines().filter(l -> l.startsWith("- ")).count();
        assertEquals(10, projectLines, "ровно top-10 проектов всех чатов: " + out);
        assertTrue(out.contains("(+more projects)"), out);
    }

    @Test
    void getChatProjects_allChats_nonTriggerUser_isDenied() {
        task("aaaa0001", 1L, "RUNNING", "allstreets/backend", "A", Instant.parse("2026-01-01T00:00:10Z"));

        String out = tools.getChatProjects(999L, true, ctx("stranger", 1L));

        assertTrue(out.contains("not allowed"), out);
        assertFalse(out.contains("allstreets/backend"), out);
    }

    @Test
    void getChatProjects_allChatsFalse_keepsPerChatBehaviour() {
        task("aaaa0001", 1L, "RUNNING", "allstreets/backend", "A", Instant.parse("2026-01-01T00:00:10Z"));
        task("bbbb0002", 2L, "RUNNING", "other/frontend", "B", Instant.parse("2026-01-01T00:00:20Z"));

        String out = tools.getChatProjects(1L, false, ctx(TRIGGER, 1L));

        assertTrue(out.contains("allstreets/backend"), out);
        assertFalse(out.contains("other/frontend"), out);
    }

    // ------------------------------------------------------------------
    // getLastTaskForChat — allChats
    // ------------------------------------------------------------------

    @Test
    void getLastTaskForChat_allChats_returnsNewestAcrossChatsWithChatId() {
        task("aaaa0001", 1L, "RUNNING", "allstreets/backend", "Old", Instant.parse("2026-01-01T00:00:10Z"));
        task("bbbb0002", 2L, "FAILED", "other/frontend", "New", Instant.parse("2026-01-01T00:00:20Z"));

        String out = tools.getLastTaskForChat(999L, true, ctx(TRIGGER, 999L));

        assertTrue(out.contains("task_id: bbbb0002"), out);
        assertTrue(out.contains("chat_id: 2"), out);
        assertFalse(out.contains("aaaa0001"), "должна вернуться только последняя задача: " + out);
    }

    @Test
    void getLastTaskForChat_allChats_ignoresDeletedTasks() {
        TaskEntity deleted = task("bbbb0002", 2L, "FAILED", "other/frontend", "New",
                Instant.parse("2026-01-01T00:00:20Z"));
        deleted.setDeleted(true);
        taskRepo.saveAndFlush(deleted);
        task("aaaa0001", 1L, "RUNNING", "allstreets/backend", "Older",
                Instant.parse("2026-01-01T00:00:10Z"));

        String out = tools.getLastTaskForChat(999L, true, ctx(TRIGGER, 999L));

        assertTrue(out.contains("task_id: aaaa0001"), out);
        assertTrue(out.contains("chat_id: 1"), out);
    }

    @Test
    void getLastTaskForChat_allChats_nonTriggerUser_isDenied() {
        task("aaaa0001", 1L, "RUNNING", "allstreets/backend", "A", Instant.parse("2026-01-01T00:00:10Z"));

        String out = tools.getLastTaskForChat(999L, true, ctx("stranger", 1L));

        assertTrue(out.contains("not allowed"), out);
        assertFalse(out.contains("aaaa0001"), out);
    }

    @Test
    void getLastTaskForChat_allChatsFalse_keepsPerChatBehaviour() {
        task("aaaa0001", 1L, "RUNNING", "allstreets/backend", "A", Instant.parse("2026-01-01T00:00:10Z"));
        task("bbbb0002", 2L, "FAILED", "other/frontend", "B", Instant.parse("2026-01-01T00:00:20Z"));

        String out = tools.getLastTaskForChat(1L, false, ctx(TRIGGER, 1L));

        assertTrue(out.contains("aaaa0001"), out);
        assertFalse(out.contains("bbbb0002"), out);
    }

    @Test
    void getChatTasks_allChats_callsTitleResolverForEachChat() {
        task("aaaa0001", 1L, "RUNNING", "allstreets/backend", "A", Instant.parse("2026-01-01T00:00:10Z"));
        task("bbbb0002", 2L, "RUNNING", "other/frontend", "B", Instant.parse("2026-01-01T00:00:20Z"));

        tools.getChatTasks(999L, 0, 10, true, null, null, null, null, ctx(TRIGGER, 999L));

        verify(titles).titleOrFallback(1L);
        verify(titles).titleOrFallback(2L);
    }

    @Test
    void getChatTasks_allChatsFalse_doesNotResolveTitles() {
        task("aaaa0001", 1L, "RUNNING", "allstreets/backend", "A", Instant.parse("2026-01-01T00:00:10Z"));

        tools.getChatTasks(1L, 0, 10, false, null, null, null, null, ctx(TRIGGER, 1L));

        verify(titles, never()).titleOrFallback(anyLong());
    }
}
