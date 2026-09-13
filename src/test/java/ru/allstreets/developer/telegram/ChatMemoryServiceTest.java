package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.Test;
import ru.allstreets.developer.checkpoint.ChatMessageRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Sliding window памяти чата жёстко ограничен ({@code WINDOW_SIZE}), поэтому контекст
 * классификатора не растёт с числом сообщений в чате.
 */
class ChatMemoryServiceTest {

    @Test
    void historyIsBounded_regardlessOfMessageCount() {
        var service = new ChatMemoryService(mock(ChatMessageRepository.class));

        for (int i = 0; i < 5000; i++) {
            service.recordUserMessage(1L, "message " + i);
        }

        var history = service.getHistory(1L);
        assertEquals(30, history.size(), "окно истории жёстко ограничено 30 сообщениями");
        assertEquals("message 4999", history.getLast().text(), "остаются самые свежие сообщения");

        String text = service.getHistoryText(1L);
        assertTrue(text.contains("user: message 4999"), "свежее сообщение в контексте");
        assertFalse(text.contains("user: message 0\n"), "старые сообщения вытеснены");
    }

    @Test
    void historiesAreIsolatedPerChat() {
        var service = new ChatMemoryService(mock(ChatMessageRepository.class));

        service.recordUserMessage(1L, "chat one");
        service.recordUserMessage(2L, "chat two");

        assertTrue(service.getHistoryText(1L).contains("chat one"));
        assertFalse(service.getHistoryText(1L).contains("chat two"));
        assertTrue(service.getHistoryText(2L).contains("chat two"));
    }
}
