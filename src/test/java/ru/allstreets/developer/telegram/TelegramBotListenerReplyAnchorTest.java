package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanInputRegistry;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Listener — единственная точка, знающая входящие message_id: каждое принятое
 * whitelist-сообщение должно зарегистрироваться в {@link ReplyAnchorRegistry},
 * чтобы немедленные ответы (/status, /start, ANSWER, …) ушли reply-to на источник.
 */
class TelegramBotListenerReplyAnchorTest {

    private static final long CHAT_ID = 1L;
    private static final String BOT = "AllstreetsAIbackendBot";

    private TelegramGateway telegram;
    private ReplyAnchorRegistry replyAnchors;
    private ConversationAgent conversationAgent;
    private HumanInputRegistry humanInputRegistry;
    private ActiveTaskRegistry taskRegistry;
    private TelegramBotListener listener;

    @BeforeEach
    void setUp() {
        telegram = mock(TelegramGateway.class);
        replyAnchors = mock(ReplyAnchorRegistry.class);
        conversationAgent = mock(ConversationAgent.class);
        humanInputRegistry = mock(HumanInputRegistry.class);
        taskRegistry = mock(ActiveTaskRegistry.class);
        listener = new TelegramBotListener(telegram, mock(TaskLauncher.class), conversationAgent,
                mock(ChatMemoryService.class), humanInputRegistry, taskRegistry,
                mock(TaskRepository.class), replyAnchors);
        ReflectionTestUtils.setField(listener, "allowedChatIdsRaw", String.valueOf(CHAT_ID));
        ReflectionTestUtils.setField(listener, "botUsername", BOT);
        listener.init();
    }

    private static TelegramGateway.Update update(long messageId, long chatId, String text) {
        var msg = new TelegramGateway.Message(messageId,
                new TelegramGateway.User(2L, false, "Дима", "dmitry"),
                new TelegramGateway.Chat(chatId, "group"), text, 0L, List.of(), null);
        return new TelegramGateway.Update(1, msg, null);
    }

    private void givenUpdates(TelegramGateway.Update... updates) {
        when(telegram.getUpdates(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TelegramGateway.TelegramUpdates(true, List.of(updates)));
    }

    @Test
    void acceptedMessage_recordsAnchor_andStatusRepliesViaGateway() {
        givenUpdates(update(501L, CHAT_ID, "/status"));

        listener.poll();

        verify(replyAnchors).recordIncoming(CHAT_ID, 501L);
        verify(telegram).sendMessage(CHAT_ID, "📋 Нет активных задач.");
    }

    @Test
    void startCommand_recordsAnchor_andRepliesViaGateway() {
        givenUpdates(update(777L, CHAT_ID, "/start"));

        listener.poll();

        verify(replyAnchors).recordIncoming(CHAT_ID, 777L);
        verify(telegram).sendMessage(org.mockito.ArgumentMatchers.eq(CHAT_ID),
                org.mockito.ArgumentMatchers.contains("Привет"));
    }

    @Test
    void nonWhitelistedChat_isIgnored() {
        givenUpdates(update(501L, 999L, "/status"));

        listener.poll();

        verify(replyAnchors, never()).recordIncoming(anyLong(), anyLong());
        verify(telegram, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void blankText_isNotRecorded() {
        givenUpdates(update(502L, CHAT_ID, "   "));

        listener.poll();

        verify(replyAnchors, never()).recordIncoming(anyLong(), anyLong());
    }

    @Test
    void noUpdates_doesNothing() {
        when(telegram.getUpdates(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TelegramGateway.TelegramUpdates(true, List.of()));

        listener.poll();

        verify(replyAnchors, never()).recordIncoming(anyLong(), anyLong());
    }

    @Test
    void latestOfSeveralMessages_wins() {
        // Listener регистрирует каждое принятое сообщение — последнее и станет fallback-anchor.
        givenUpdates(update(10L, CHAT_ID, "/status"), update(11L, CHAT_ID, "/status"));

        listener.poll();

        verify(replyAnchors).recordIncoming(CHAT_ID, 10L);
        verify(replyAnchors).recordIncoming(CHAT_ID, 11L);
    }
}
