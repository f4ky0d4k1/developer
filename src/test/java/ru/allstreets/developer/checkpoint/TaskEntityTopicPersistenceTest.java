package ru.allstreets.developer.checkpoint;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import ru.allstreets.developer.PostgresTestBase;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Хранилище forum-тем (BACKEND-443, T1/T5): nullable-колонки
 * {@code thread_id}/{@code root_thread_id}/{@code parent_task_id} на {@code agent_tasks},
 * поиск задачи по паре {@code (notify_chat_id, thread_id)} и подъём по цепочке
 * {@code parent_task_id}.
 * <p>
 * Тема принадлежит только notify-чату задачи; задача может быть видна в нескольких
 * чатах (cross-chat, BACKEND-441), поэтому привязка входящих считается по паре
 * {@code (chat_id, thread_id)}, а не по одной колонке.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TaskEntityTopicPersistenceTest extends PostgresTestBase {

    private static final long CHAT = -100L;

    @Autowired
    private TaskRepository taskRepo;

    @Test
    void rootTask_persistsThreadAndRootThread() {
        TaskEntity root = new TaskEntity("root-1", "RUNNING", "d", "Тема", CHAT);
        root.setThreadId(500L);
        root.setRootThreadId(500L);
        taskRepo.saveAndFlush(root);

        TaskEntity found = taskRepo.findById("root-1").orElseThrow();
        assertEquals(Long.valueOf(500L), found.getThreadId());
        assertEquals(Long.valueOf(500L), found.getRootThreadId());
        assertNull(found.getParentTaskId(), "у корня нет parent_task_id");
    }

    @Test
    void terminalTask_hasNoThread() {
        TaskEntity terminal = new TaskEntity("term-1", "COMPLETED", "d", "t", CHAT);
        taskRepo.saveAndFlush(terminal);

        assertNull(taskRepo.findById("term-1").orElseThrow().getThreadId(),
                "терминальные задачи тему не получают");
    }

    @Test
    void childTask_inheritsRootThread_andLinksParent() {
        TaskEntity root = new TaskEntity("root-2", "RUNNING", "d", "Тема", CHAT);
        root.setThreadId(600L);
        root.setRootThreadId(600L);
        taskRepo.saveAndFlush(root);

        TaskEntity child = new TaskEntity("child-2", "RUNNING", "d", "Дочерняя", CHAT);
        child.setParentTaskId("root-2");
        child.setThreadId(600L);      // та же тема, что у корня
        child.setRootThreadId(600L);
        taskRepo.saveAndFlush(child);

        TaskEntity found = taskRepo.findById("child-2").orElseThrow();
        assertEquals(Long.valueOf(600L), found.getThreadId(), "наследник не создаёт свою тему");
        assertEquals(Long.valueOf(600L), found.getRootThreadId());
        assertEquals("root-2", found.getParentTaskId());
    }

    @Test
    void findRootByChatAndThread_returnsCanonicalRootTask() {
        TaskEntity root = new TaskEntity("root-3", "RUNNING", "d", "Тема", CHAT);
        root.setThreadId(700L);
        root.setRootThreadId(700L);
        taskRepo.saveAndFlush(root);

        TaskEntity child = new TaskEntity("child-3", "RUNNING", "d", "Дочерняя", CHAT);
        child.setParentTaskId("root-3");
        child.setThreadId(700L);
        child.setRootThreadId(700L);
        taskRepo.saveAndFlush(child);

        var found = taskRepo.findByNotifyChatIdAndThreadIdAndParentTaskIdIsNull(CHAT, 700L);

        assertTrue(found.isPresent(), "роутинг по (chat_id, thread_id) должен найти задачу темы");
        assertEquals("root-3", found.get().getTaskId(),
                "канонический владелец темы — корень (parent_task_id IS NULL)");
    }

    @Test
    void findByParentTaskId_returnsFullChain() {
        TaskEntity root = new TaskEntity("root-4", "RUNNING", "d", "Тема", CHAT);
        root.setThreadId(800L);
        root.setRootThreadId(800L);
        taskRepo.saveAndFlush(root);

        for (String id : List.of("child-4a", "child-4b")) {
            TaskEntity child = new TaskEntity(id, "RUNNING", "d", "Дочерняя", CHAT);
            child.setParentTaskId("root-4");
            child.setThreadId(800L);
            child.setRootThreadId(800L);
            taskRepo.saveAndFlush(child);
        }

        List<String> children = taskRepo.findByParentTaskId("root-4").stream()
                .map(TaskEntity::getTaskId)
                .sorted()
                .toList();

        assertEquals(List.of("child-4a", "child-4b"), children);
    }

    @Test
    void findRootByChatAndThread_wrongThread_returnsEmpty() {
        TaskEntity root = new TaskEntity("root-5", "RUNNING", "d", "Тема", CHAT);
        root.setThreadId(900L);
        root.setRootThreadId(900L);
        taskRepo.saveAndFlush(root);

        assertTrue(taskRepo.findByNotifyChatIdAndThreadIdAndParentTaskIdIsNull(CHAT, 901L).isEmpty());
    }
}
