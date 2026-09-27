package ru.allstreets.developer.humanloop;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.allstreets.developer.telegram.TelegramGateway;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * HITL-вопрос обязан уходить в forum-тему задачи (BACKEND-443, AC «нет утечки в General»).
 * <p>
 * {@link HumanLoopService#askHuman} знает taskId и ДОЛЖЕН передавать его в
 * {@link TelegramGateway#sendMessage(long, String, String)} — иначе резолвер не подставит
 * {@code message_thread_id} и вопрос утечёт в General.
 */
class HumanLoopServiceTest {

    private static final long CHAT = -100123L;
    private static final String TASK = "aaaaaaaa-1111-2222-3333-444455556666";

    @Test
    void askHuman_passesTaskId_soQuestionGoesToTaskTopic() {
        HumanInputRegistry registry = mock(HumanInputRegistry.class);
        TelegramGateway telegram = mock(TelegramGateway.class);
        HumanLoopService service = new HumanLoopService(registry, telegram);

        service.askHuman(TASK, CHAT, "Уточни репозиторий\\nи ветку");

        verify(telegram).sendMessage(eq(CHAT), org.mockito.ArgumentMatchers.contains("Уточни репозиторий"), eq(TASK));
        ArgumentCaptor<String> question = ArgumentCaptor.forClass(String.class);
        verify(registry).registerPending(eq(TASK), eq(CHAT), question.capture());
        assertTrue(question.getValue().contains("Уточни репозиторий"),
                "в registry должен уйти исходный вопрос");
    }
}
