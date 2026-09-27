package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Контракт {@code TelegramTopicService} (BACKEND-443, T2/T6/T7).
 * <p>
 * Сервис — единая точка жизненного цикла forum-темы задачи:
 * <ul>
 *   <li>резолвер {@code taskId → threadId} с кэшем, истина в БД, кэш перестраивается
 *       из БД после рестарта (invalidate/повторное чтение);</li>
 *   <li>санитизация имени темы (одна строка, ≤128, непустое, fallback на ID);</li>
 *   <li>идемпотентное создание темы: атомарный claim через
 *       {@link TaskRepository#claimThreadId(String, Long)}, проигравший гонку
 *       перечитывает победивший thread_id и НЕ перезаписывает его;</li>
 *   <li>наследование темы корня для наследника ({@code parentTaskId}), своя тема
 *       не создаётся;</li>
 *   <li>fail-open при недоступности Telegram (удалённая тема / нет прав / 429):
 *       создание возвращает {@code null} (вызывающий уходит в fallback), lifecycle-методы
 *       не бросают исключений и не валят задачу.</li>
 * </ul>
 */
class TelegramTopicServiceTest {

    private static final long CHAT = -100123L;
    private static final String ROOT = "root-task-0001";
    private static final String CHILD = "child-task-0002";

    private TelegramGateway gateway;
    private TaskRepository taskRepo;
    private TelegramTopicService service;

    @BeforeEach
    void setUp() {
        gateway = mock(TelegramGateway.class);
        taskRepo = mock(TaskRepository.class);
        service = new TelegramTopicService(gateway, taskRepo);
    }

    // ------------------------------------------------------------------
    // Санитизация имени темы (AC: одна строка, ≤128, непустое, fallback на ID)
    // ------------------------------------------------------------------

    @Test
    void sanitize_removesLineBreaks() {
        String name = TelegramTopicService.sanitizeTopicName("Задача\nс\r\nпереносом\tи табом", ROOT);

        assertFalse(name.contains("\n"), name);
        assertFalse(name.contains("\r"), name);
        assertFalse(name.contains("\t"), name);
    }

    @Test
    void sanitize_isAtMost128Chars() {
        String name = TelegramTopicService.sanitizeTopicName("x".repeat(500), ROOT);

        assertTrue(name.length() <= 128, "длина имени темы должна быть ≤128, было " + name.length());
        assertFalse(name.isBlank(), name);
    }

    @Test
    void sanitize_keepsTitle() {
        String name = TelegramTopicService.sanitizeTopicName("Вынести техучётки", ROOT);

        assertTrue(name.contains("Вынести техучётки"), name);
        assertTrue(name.length() <= 128, name);
    }

    @Test
    void sanitize_blankTitle_fallsBackToTaskId() {
        String name = TelegramTopicService.sanitizeTopicName("   ", ROOT);

        assertFalse(name.isBlank(), name);
        // fallback обязан опираться на ID задачи, иначе тему невозможно сопоставить с задачей
        assertTrue(name.contains("root-tas"), name);
    }

    @Test
    void sanitize_nullTitle_fallsBackToTaskId() {
        String name = TelegramTopicService.sanitizeTopicName(null, ROOT);

        assertFalse(name.isBlank(), name);
        assertTrue(name.contains("root-tas"), name);
    }

    @Test
    void sanitize_nullTaskId_doesNotThrow_andIsNotBlank() {
        String name = TelegramTopicService.sanitizeTopicName("title", null);

        assertFalse(name.isBlank(), name);
    }

    // ------------------------------------------------------------------
    // Резолвер taskId → threadId: истина в БД, кэш инвалидируется
    // ------------------------------------------------------------------

    @Test
    void resolveThreadId_readsDbOnceThenServesFromCache() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, 100L)));

        assertEquals(Long.valueOf(100L), service.resolveThreadId(ROOT));
        assertEquals(Long.valueOf(100L), service.resolveThreadId(ROOT));

        verify(taskRepo, times(1)).findById(ROOT);
    }

    @Test
    void resolveThreadId_unknownTask_returnsNull() {
        when(taskRepo.findById("nope")).thenReturn(Optional.empty());

        assertNull(service.resolveThreadId("nope"));
    }

    @Test
    void resolveThreadId_taskWithoutTopic_returnsNull() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, null)));

        assertNull(service.resolveThreadId(ROOT));
    }

    @Test
    void invalidate_forcesReReadFromDb_afterRestartOrReassign() {
        when(taskRepo.findById(ROOT)).thenReturn(
                Optional.of(task(ROOT, 100L)),
                Optional.of(task(ROOT, 200L)));

        assertEquals(Long.valueOf(100L), service.resolveThreadId(ROOT));
        service.invalidate(ROOT); // кэш перестраивается из БД (рестарт / reassign)
        assertEquals(Long.valueOf(200L), service.resolveThreadId(ROOT));

        verify(taskRepo, times(2)).findById(ROOT);
    }

    // ------------------------------------------------------------------
    // Идемпотентное создание темы + гонка
    // ------------------------------------------------------------------

    @Test
    void ensureTopic_existingThread_returnsItWithoutCreating() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, 77L)));

        assertEquals(Long.valueOf(77L), service.ensureTopic(ROOT, CHAT, "Название"));

        verify(gateway, never()).createForumTopic(anyLong(), anyString());
    }

    @Test
    void ensureTopic_createsAndClaimsOnce() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, null)));
        when(gateway.createForumTopic(eq(CHAT), anyString())).thenReturn(555L);
        when(taskRepo.claimThreadId(ROOT, 555L)).thenReturn(1);

        assertEquals(Long.valueOf(555L), service.ensureTopic(ROOT, CHAT, "Название"));

        verify(gateway, times(1)).createForumTopic(eq(CHAT), anyString());
        verify(taskRepo).claimThreadId(ROOT, 555L);
    }

    @Test
    void ensureTopic_lostRace_reReadsWinner_andDoesNotOverwrite() {
        // Первое чтение — thread_id ещё пуст; после проигранного claim БД уже содержит чужой id.
        when(taskRepo.findById(ROOT)).thenReturn(
                Optional.of(task(ROOT, null)),
                Optional.of(task(ROOT, 999L)));
        when(gateway.createForumTopic(eq(CHAT), anyString())).thenReturn(555L);
        when(taskRepo.claimThreadId(ROOT, 555L)).thenReturn(0); // UPDATE ... WHERE thread_id IS NULL не затронул строку

        assertEquals(Long.valueOf(999L), service.ensureTopic(ROOT, CHAT, "Название"),
                "проигравший гонку обязан вернуть thread_id победителя, а не свой");
        verify(taskRepo, never()).claimThreadId(ROOT, 999L);
    }

    @Test
    void ensureTopic_usesSanitizedSingleLineName() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, null)));
        when(gateway.createForumTopic(eq(CHAT), anyString())).thenReturn(1L);
        when(taskRepo.claimThreadId(ROOT, 1L)).thenReturn(1);

        service.ensureTopic(ROOT, CHAT, "Задача\nсо  переносами\tи   пробелами");

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway).createForumTopic(eq(CHAT), captor.capture());
        String sentName = captor.getValue();
        assertFalse(sentName.contains("\n"), sentName);
        assertTrue(sentName.length() <= 128, sentName);
    }

    // ------------------------------------------------------------------
    // Наследование темы корня (T6)
    // ------------------------------------------------------------------

    @Test
    void ensureTopic_childInheritsRootTopic_withoutCreating() {
        TaskEntity parent = task(ROOT, 321L);
        TaskEntity child = new TaskEntity(CHILD, "RUNNING", "d", "Дочерняя", CHAT);
        child.setParentTaskId(ROOT);
        when(taskRepo.findById(CHILD)).thenReturn(Optional.of(child));
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(parent));

        assertEquals(Long.valueOf(321L), service.ensureTopic(CHILD, CHAT, "Дочерняя"));

        verify(gateway, never()).createForumTopic(anyLong(), anyString());
        assertEquals(Long.valueOf(321L), child.getThreadId(), "наследник должен унаследовать топик корня");
        assertEquals(Long.valueOf(321L), child.getRootThreadId(), "корневая тема наследника = тема корня");
    }

    @Test
    void ensureTopic_grandchildInheritsRootTopic_withoutCreating() {
        TaskEntity root = task(ROOT, 321L);
        TaskEntity child = new TaskEntity("child-x", "RUNNING", "d", "Дочерняя", CHAT);
        child.setParentTaskId(ROOT);
        TaskEntity grandchild = new TaskEntity("grand-x", "RUNNING", "d", "Внук", CHAT);
        grandchild.setParentTaskId("child-x");
        when(taskRepo.findById("grand-x")).thenReturn(Optional.of(grandchild));
        when(taskRepo.findById("child-x")).thenReturn(Optional.of(child));
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(root));

        assertEquals(Long.valueOf(321L), service.ensureTopic("grand-x", CHAT, "Внук"),
                "цепочка наследников поднимается до темы корня");

        verify(gateway, never()).createForumTopic(anyLong(), anyString());
    }

    @Test
    void ensureTopic_childWithParentTopic_resolvesViaCache_noCreate() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, 321L)));
        // прогреваем кэш темы корня
        assertEquals(Long.valueOf(321L), service.resolveThreadId(ROOT));

        TaskEntity child = new TaskEntity(CHILD, "RUNNING", "d", "Дочерняя", CHAT);
        child.setParentTaskId(ROOT);
        when(taskRepo.findById(CHILD)).thenReturn(Optional.of(child));

        assertEquals(Long.valueOf(321L), service.ensureTopic(CHILD, CHAT, "Дочерняя"));
        verify(gateway, never()).createForumTopic(anyLong(), anyString());
    }

    // ------------------------------------------------------------------
    // Fallback: удалённая тема / потеря прав / 429 (fail-open)
    // ------------------------------------------------------------------

    @Test
    void ensureTopic_createUnavailable_returnsNull_failOpen() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, null)));
        when(gateway.createForumTopic(eq(CHAT), anyString())).thenReturn(null);

        assertNull(service.ensureTopic(ROOT, CHAT, "Название"),
                "нет темы (403/429/удалена) → null, вызывающий уходит в fallback");
        verify(taskRepo, never()).claimThreadId(anyString(), anyLong());
    }

    @Test
    void ensureTopic_gatewayThrows_returnsNull_failOpen() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, null)));
        when(gateway.createForumTopic(eq(CHAT), anyString()))
                .thenThrow(new RuntimeException("Forbidden: not enough rights to manage topics"));

        assertNull(service.ensureTopic(ROOT, CHAT, "Название"));
    }

    // ------------------------------------------------------------------
    // Жизненный цикл: rename/close/pin идемпотентны и fail-open (T7)
    // ------------------------------------------------------------------

    @Test
    void renameTopic_knownThread_callsGatewayWithSanitizedNewName() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, 88L)));
        when(gateway.editForumTopic(CHAT, 88L, "✅ Задача 1")).thenReturn(true);

        assertTrue(service.renameTopic(ROOT, "✅ Задача 1"));

        verify(gateway).editForumTopic(CHAT, 88L, "✅ Задача 1");
    }

    @Test
    void renameTopic_noThread_skipsGateway() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, null)));

        assertFalse(service.renameTopic(ROOT, "новое имя"));

        verify(gateway, never()).editForumTopic(anyLong(), anyLong(), anyString());
    }

    @Test
    void renameTopic_gatewayThrows_failOpen() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, 88L)));
        when(gateway.editForumTopic(anyLong(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("Bad Request: thread not found"));

        assertFalse(service.renameTopic(ROOT, "новое имя"));
    }

    @Test
    void closeTopic_knownThread_callsGateway() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, 88L)));
        when(gateway.closeForumTopic(CHAT, 88L)).thenReturn(true);

        assertTrue(service.closeTopic(ROOT));

        verify(gateway).closeForumTopic(CHAT, 88L);
    }

    @Test
    void closeTopic_noThread_skipsGateway() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, null)));

        assertFalse(service.closeTopic(ROOT));

        verify(gateway, never()).closeForumTopic(anyLong(), anyLong());
    }

    @Test
    void closeTopic_gatewayThrows_failOpen() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, 88L)));
        when(gateway.closeForumTopic(anyLong(), anyLong()))
                .thenThrow(new RuntimeException("Bad Request: TOPIC_CLOSED"));

        assertFalse(service.closeTopic(ROOT));
    }

    @Test
    void pinTopicDescription_gatewayThrows_failOpen() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, 88L)));
        when(gateway.pinChatMessage(anyLong(), anyLong()))
                .thenThrow(new RuntimeException("Bad Request: not enough rights to pin"));

        assertFalse(service.pinTopicDescription(ROOT, "описание задачи"));
    }

    @Test
    void pinTopicDescription_knownThread_callsGatewayOnce() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, 88L)));
        when(gateway.pinChatMessage(CHAT, 88L)).thenReturn(true);

        assertTrue(service.pinTopicDescription(ROOT, "описание задачи"));

        verify(gateway).pinChatMessage(CHAT, 88L);
    }

    @Test
    void pinTopicDescription_noThread_skipsGateway() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, null)));

        assertFalse(service.pinTopicDescription(ROOT, "описание задачи"));

        verify(gateway, never()).pinChatMessage(anyLong(), anyLong());
    }

    @Test
    void closeTopic_repeated_isIdempotent() {
        when(taskRepo.findById(ROOT)).thenReturn(Optional.of(task(ROOT, 88L)));
        when(gateway.closeForumTopic(CHAT, 88L)).thenReturn(true);

        assertTrue(service.closeTopic(ROOT));
        assertTrue(service.closeTopic(ROOT));

        verify(gateway, times(2)).closeForumTopic(CHAT, 88L);
    }

    private static TaskEntity task(String taskId, Long threadId) {
        TaskEntity task = new TaskEntity(taskId, "RUNNING", "desc", "title", CHAT);
        task.setThreadId(threadId);
        task.setRootThreadId(threadId);
        return task;
    }
}
