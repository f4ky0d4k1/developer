package ru.allstreets.developer.humanloop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.checkpoint.PendingInputEntity;
import ru.allstreets.developer.checkpoint.PendingInputRepository;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pending HITL-вопросы должны читаться из БД, а не из памяти: после рестарта (деплой)
 * ответ пользователя не находил pending и задача навсегда оставалась в HITL
 * (инцидент 427edb3c: «нет chatId для pending задачи — resume невозможен»).
 */
class HumanInputRegistryTest {

    private static final String TASK_ID = "427edb3c-0000-0000-0000-000000000000";
    private static final long CHAT_ID = 1270746894L;

    private PendingInputRepository pendingRepo;
    private TaskRepository taskRepo;
    private HumanInputRegistry registry;

    @BeforeEach
    void setUp() {
        pendingRepo = mock(PendingInputRepository.class);
        taskRepo = mock(TaskRepository.class);
        registry = new HumanInputRegistry(pendingRepo, taskRepo);
    }

    private TaskEntity runningTask() {
        return new TaskEntity(TASK_ID, "RUNNING", "d", "t", CHAT_ID);
    }

    @Test
    void getChatIdForPending_readsFromDb_notMemory() {
        when(pendingRepo.findById(TASK_ID))
                .thenReturn(Optional.of(new PendingInputEntity(TASK_ID, CHAT_ID, "q")));
        when(taskRepo.findById(TASK_ID)).thenReturn(Optional.of(runningTask()));

        assertEquals(CHAT_ID, registry.getChatIdForPending(TASK_ID));
    }

    @Test
    void getChatIdForPending_returnsNull_whenAbsent() {
        when(pendingRepo.findById(TASK_ID)).thenReturn(Optional.empty());

        assertNull(registry.getChatIdForPending(TASK_ID));
    }

    @Test
    void getChatIdForPending_returnsNull_andPurges_whenTaskGone() {
        when(pendingRepo.findById(TASK_ID))
                .thenReturn(Optional.of(new PendingInputEntity(TASK_ID, CHAT_ID, "q")));
        when(taskRepo.findById(TASK_ID)).thenReturn(Optional.empty());

        assertNull(registry.getChatIdForPending(TASK_ID));
        verify(pendingRepo).deleteById(TASK_ID);
    }

    @Test
    void hasPendingInputs_readsFromDb() {
        when(pendingRepo.findByChatId(CHAT_ID))
                .thenReturn(List.of(new PendingInputEntity(TASK_ID, CHAT_ID, "q")));
        when(taskRepo.findById(TASK_ID)).thenReturn(Optional.of(runningTask()));

        assertTrue(registry.hasPendingInputs(CHAT_ID));

        when(pendingRepo.findByChatId(CHAT_ID)).thenReturn(List.of());
        assertFalse(registry.hasPendingInputs(CHAT_ID));
    }

    @Test
    void hasPendingInputs_false_whenOnlyOrphanedQuestions() {
        when(pendingRepo.findByChatId(CHAT_ID))
                .thenReturn(List.of(new PendingInputEntity(TASK_ID, CHAT_ID, "q")));
        when(taskRepo.findById(TASK_ID)).thenReturn(Optional.empty());

        assertFalse(registry.hasPendingInputs(CHAT_ID));
        verify(pendingRepo).deleteById(TASK_ID);
    }

    @Test
    void getPendingQuestionsForChat_readsFromDb() {
        when(pendingRepo.findByChatId(CHAT_ID))
                .thenReturn(List.of(new PendingInputEntity(TASK_ID, CHAT_ID, "Вопрос?")));
        when(taskRepo.findById(TASK_ID)).thenReturn(Optional.of(runningTask()));

        assertEquals(Map.of(TASK_ID, "Вопрос?"), registry.getPendingQuestionsForChat(CHAT_ID));
        verify(pendingRepo, never()).deleteById(anyString());
    }

    @Test
    void getPendingQuestionsForChat_filtersOrphanedTask_notInDb() {
        when(pendingRepo.findByChatId(CHAT_ID))
                .thenReturn(List.of(new PendingInputEntity(TASK_ID, CHAT_ID, "Вопрос?")));
        when(taskRepo.findById(TASK_ID)).thenReturn(Optional.empty());

        assertTrue(registry.getPendingQuestionsForChat(CHAT_ID).isEmpty());
        verify(pendingRepo).deleteById(TASK_ID);
    }

    @Test
    void getPendingQuestionsForChat_filtersClosedOrCompletedTask() {
        when(pendingRepo.findByChatId(CHAT_ID))
                .thenReturn(List.of(new PendingInputEntity(TASK_ID, CHAT_ID, "Вопрос?")));
        TaskEntity closed = new TaskEntity(TASK_ID, "CLOSED", "d", "t", CHAT_ID);
        when(taskRepo.findById(TASK_ID)).thenReturn(Optional.of(closed));

        assertTrue(registry.getPendingQuestionsForChat(CHAT_ID).isEmpty());
        verify(pendingRepo).deleteById(TASK_ID);
    }

    @Test
    void getPendingQuestionsForChat_filtersSoftDeletedTask() {
        when(pendingRepo.findByChatId(CHAT_ID))
                .thenReturn(List.of(new PendingInputEntity(TASK_ID, CHAT_ID, "Вопрос?")));
        TaskEntity deleted = runningTask();
        deleted.setDeleted(true);
        when(taskRepo.findById(TASK_ID)).thenReturn(Optional.of(deleted));

        assertTrue(registry.getPendingQuestionsForChat(CHAT_ID).isEmpty());
        verify(pendingRepo).deleteById(TASK_ID);
    }

    @Test
    void registerPending_persistsToDb() {
        registry.registerPending(TASK_ID, CHAT_ID, "Вопрос?");

        verify(pendingRepo).save(argThat(e -> TASK_ID.equals(e.getTaskId())
                && e.getChatId() == CHAT_ID
                && "Вопрос?".equals(e.getQuestion())));
    }

    @Test
    void provideAnswer_deletesFromDb() {
        when(pendingRepo.existsById(TASK_ID)).thenReturn(true);

        registry.provideAnswer(TASK_ID);

        verify(pendingRepo).deleteById(TASK_ID);
    }

    @Test
    void provideAnswer_doesNothing_whenAbsent() {
        when(pendingRepo.existsById(TASK_ID)).thenReturn(false);

        registry.provideAnswer(TASK_ID);

        verify(pendingRepo, never()).deleteById(anyString());
    }

    @Test
    void cancel_deletesFromDb() {
        when(pendingRepo.existsById(TASK_ID)).thenReturn(true);

        registry.cancel(TASK_ID);

        verify(pendingRepo).deleteById(TASK_ID);
    }
}
