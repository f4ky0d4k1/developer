package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.allstreets.developer.agents.AgentResponses;
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
 * Mention-free чаты (telegram.mention-free-chat-ids): бот отвечает на ВСЕ сообщения
 * без @mention, в отличие от обычных групп, где без упоминания сообщение пропускается.
 */
class TelegramBotListenerMentionFreeTest {

    private static final long MENTION_FREE_CHAT_ID = 1L;
    private static final long REGULAR_GROUP_ID = 2L;
    private static final String BOT = "AllstreetsAIbackendBot";

    private TelegramGateway telegram;
    private ConversationAgent conversationAgent;
    private HumanInputRegistry humanInputRegistry;
    private TelegramBotListener listener;

    @BeforeEach
    void setUp() {
        telegram = mock(TelegramGateway.class);
        conversationAgent = mock(ConversationAgent.class);
        humanInputRegistry = mock(HumanInputRegistry.class);
        listener = new TelegramBotListener(telegram, mock(TaskLauncher.class), conversationAgent,
                mock(ChatMemoryService.class), humanInputRegistry, mock(ActiveTaskRegistry.class),
                mock(TaskRepository.class), mock(ReplyAnchorRegistry.class));
        ReflectionTestUtils.setField(listener, "allowedChatIdsRaw",
                MENTION_FREE_CHAT_ID + "," + REGULAR_GROUP_ID);
        ReflectionTestUtils.setField(listener, "mentionFreeChatIdsRaw",
                String.valueOf(MENTION_FREE_CHAT_ID));
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
    void mentionFreeChat_processesMessageWithoutMention() {
        when(conversationAgent.processMessage(anyLong(), anyString(), anyString()))
                .thenReturn(new ConversationAgent.Decision(AgentResponses.OrchestratorAction.ANSWER, null, "ok", null, null));
        givenUpdates(update(501L, MENTION_FREE_CHAT_ID, "поставь задачу без упоминания"));

        listener.poll();

        verify(conversationAgent).processMessage(org.mockito.ArgumentMatchers.eq(MENTION_FREE_CHAT_ID),
                org.mockito.ArgumentMatchers.eq("dmitry"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void regularGroup_skipsMessageWithoutMention() {
        givenUpdates(update(502L, REGULAR_GROUP_ID, "поставь задачу без упоминания"));

        listener.poll();

        verify(conversationAgent, never()).processMessage(anyLong(), anyString(), anyString());
    }
}
