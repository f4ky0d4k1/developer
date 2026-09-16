package ru.allstreets.developer.humanloop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.checkpoint.PendingInputEntity;
import ru.allstreets.developer.checkpoint.PendingInputRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private HumanInputRegistry registry;

    @BeforeEach
    void setUp() {
        pendingRepo = mock(PendingInputRepository.class);
        registry = new HumanInputRegistry(pendingRepo);
    }

    @Test
    void getChatIdForPending_readsFromDb_notMemory() {
        when(pendingRepo.findById(TASK_ID))
                .thenReturn(Optional.of(new PendingInputEntity(TASK_ID, CHAT_ID, "q")));

        assertEquals(CHAT_ID, registry.getChatIdForPending(TASK_ID));
    }

    @Test
    void getChatIdForPending_returnsNull_whenAbsent() {
        when(pendingRepo.findById(TASK_ID)).thenReturn(Optional.empty());

        assertNull(registry.getChatIdForPending(TASK_ID));
    }

    @Test
    void hasPendingInputs_readsFromDb() {
        when(pendingRepo.findByChatId(CHAT_ID))
                .thenReturn(List.of(new PendingInputEntity(TASK_ID, CHAT_ID, "q")));

        assertTrue(registry.hasPendingInputs(CHAT_ID));

        when(pendingRepo.findByChatId(CHAT_ID)).thenReturn(List.of());
        assertFalse(registry.hasPendingInputs(CHAT_ID));
    }

    @Test
    void getPendingQuestionsForChat_readsFromDb() {
        when(pendingRepo.findByChatId(CHAT_ID))
                .thenReturn(List.of(new PendingInputEntity(TASK_ID, CHAT_ID, "Вопрос?")));

        assertEquals(Map.of(TASK_ID, "Вопрос?"), registry.getPendingQuestionsForChat(CHAT_ID));
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
