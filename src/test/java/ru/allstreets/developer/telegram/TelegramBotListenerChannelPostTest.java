package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.allstreets.developer.agents.AgentResponses;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanInputRegistry;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Канальные посты (channel_post): бот — админ канала, реагирует на ВСЕ посты без @mention.
 * Пост «от имени канала» приходит с {@code from=null} (источник — {@code sender_chat}, не User),
 * поэтому username уходит в классификатор как null.
 */
class TelegramBotListenerChannelPostTest {

    private static final long CHANNEL_ID = 1L;
    private static final String BOT = "AllstreetsAIbackendBot";

    private TelegramGateway telegram;
    private ConversationAgent conversationAgent;
    private TelegramBotListener listener;

    @BeforeEach
    void setUp() {
        telegram = mock(TelegramGateway.class);
        conversationAgent = mock(ConversationAgent.class);
        listener = new TelegramBotListener(telegram, mock(TaskLauncher.class), conversationAgent,
                mock(ChatMemoryService.class), mock(HumanInputRegistry.class), mock(ActiveTaskRegistry.class),
                mock(TaskRepository.class), mock(ReplyAnchorRegistry.class));
        ReflectionTestUtils.setField(listener, "allowedChatIdsRaw", String.valueOf(CHANNEL_ID));
        ReflectionTestUtils.setField(listener, "botUsername", BOT);
        listener.init();
    }

    private static TelegramGateway.Update channelPost(long messageId, long chatId, String text) {
        // from=null: пост «от имени канала» — автор в sender_chat, не в User.
        var msg = new TelegramGateway.Message(messageId, null,
                new TelegramGateway.Chat(chatId, "channel"), text, 0L, List.of(), null);
        return new TelegramGateway.Update(1, null, null, msg);
    }

    @Test
    void channelPost_processesWithoutMention() {
        when(conversationAgent.processMessage(anyLong(), nullable(String.class), anyString()))
                .thenReturn(new ConversationAgent.Decision(AgentResponses.FastAction.ANSWER, null, "ok", null, null));
        when(telegram.getUpdates(anyInt(), anyInt()))
                .thenReturn(new TelegramGateway.TelegramUpdates(true, List.of(
                        channelPost(501L, CHANNEL_ID, "🔥 Найдена ошибка! Слой = Бой"))));

        listener.poll();

        verify(conversationAgent).processMessage(eq(CHANNEL_ID), eq(null), anyString());
    }

    @Test
    void channelPost_notInWhitelist_isIgnored() {
        long otherChannel = 999L;
        when(telegram.getUpdates(anyInt(), anyInt()))
                .thenReturn(new TelegramGateway.TelegramUpdates(true, List.of(
                        channelPost(502L, otherChannel, "алерт вне whitelist"))));

        listener.poll();

        verify(conversationAgent, never()).processMessage(anyLong(), nullable(String.class), anyString());
    }
}
