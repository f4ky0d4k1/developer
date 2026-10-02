package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import ru.allstreets.developer.agents.AgentResponses;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanInputRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Батчинг входящих сообщений: несколько сообщений одного чата, пришедших подряд,
 * уходят оркестратору (ConversationAgent) одной пачкой, а не по одному.
 */
class TelegramBotListenerBatchTest {

    private static final long CHAT_ID = 1L;
    private static final long OTHER_CHAT_ID = 2L;
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
        ReflectionTestUtils.setField(listener, "allowedChatIdsRaw", CHAT_ID + "," + OTHER_CHAT_ID);
        ReflectionTestUtils.setField(listener, "mentionFreeChatIdsRaw", CHAT_ID + "," + OTHER_CHAT_ID);
        ReflectionTestUtils.setField(listener, "botUsername", BOT);
        listener.init();
    }

    private static TelegramGateway.Update update(long messageId, long chatId, String text) {
        var msg = new TelegramGateway.Message(messageId,
                new TelegramGateway.User(2L, false, "Дима", "dmitry"),
                new TelegramGateway.Chat(chatId, "group"), text, 0L, List.of(), null);
        return new TelegramGateway.Update((int) messageId, msg, null);
    }

    @Test
    void multipleMessagesInOnePoll_flushAsSingleBatch() {
        when(conversationAgent.processMessage(anyLong(), anyString(), anyString()))
                .thenReturn(new ConversationAgent.Decision(AgentResponses.OrchestratorAction.ANSWER, null, "ok", null, null));
        when(telegram.getUpdates(anyInt(), anyInt()))
                .thenReturn(new TelegramGateway.TelegramUpdates(true, List.of(
                        update(501L, CHAT_ID, "первое сообщение"),
                        update(502L, CHAT_ID, "второе сообщение"),
                        update(503L, CHAT_ID, "третье сообщение"))));

        listener.poll();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(conversationAgent, times(1)).processMessage(eq(CHAT_ID), eq("dmitry"), captor.capture());
        String combined = captor.getValue();
        assertTrue(combined.contains("первое сообщение"), combined);
        assertTrue(combined.contains("второе сообщение"), combined);
        assertTrue(combined.contains("третье сообщение"), combined);
    }

    @Test
    void messagesAcrossPolls_coalesceWithinDebounceWindow() {
        ReflectionTestUtils.setField(listener, "batchDebounceMs", 10_000L);
        when(conversationAgent.processMessage(anyLong(), anyString(), anyString()))
                .thenReturn(new ConversationAgent.Decision(AgentResponses.OrchestratorAction.ANSWER, null, "ok", null, null));
        when(telegram.getUpdates(anyInt(), anyInt()))
                .thenReturn(new TelegramGateway.TelegramUpdates(true, List.of(update(501L, CHAT_ID, "первое сообщение"))))
                .thenReturn(new TelegramGateway.TelegramUpdates(true, List.of(update(502L, CHAT_ID, "второе сообщение"))));

        listener.poll();
        listener.poll();

        // Debounce-окно ещё не истекло — классификатор не должен вызываться.
        verify(conversationAgent, never()).processMessage(anyLong(), anyString(), anyString());

        // «Прокручиваем» время за окно — пачка флашится одним вызовом с обоими сообщениями.
        listener.flushDueBatches(System.currentTimeMillis() + 20_000L);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(conversationAgent, times(1)).processMessage(eq(CHAT_ID), eq("dmitry"), captor.capture());
        String combined = captor.getValue();
        assertTrue(combined.contains("первое сообщение"), combined);
        assertTrue(combined.contains("второе сообщение"), combined);
    }

    @Test
    void singleMessage_stillClassifiedOnce() {
        when(conversationAgent.processMessage(anyLong(), anyString(), anyString()))
                .thenReturn(new ConversationAgent.Decision(AgentResponses.OrchestratorAction.ANSWER, null, "ok", null, null));
        when(telegram.getUpdates(anyInt(), anyInt()))
                .thenReturn(new TelegramGateway.TelegramUpdates(true, List.of(update(501L, CHAT_ID, "одно сообщение"))));

        listener.poll();

        verify(conversationAgent, times(1)).processMessage(anyLong(), anyString(), anyString());
    }

    @Test
    void messagesFromDifferentChats_doNotMix() {
        when(conversationAgent.processMessage(anyLong(), anyString(), anyString()))
                .thenReturn(new ConversationAgent.Decision(AgentResponses.OrchestratorAction.ANSWER, null, "ok", null, null));
        when(telegram.getUpdates(anyInt(), anyInt()))
                .thenReturn(new TelegramGateway.TelegramUpdates(true, List.of(
                        update(501L, CHAT_ID, "сообщение в первом чате"),
                        update(502L, OTHER_CHAT_ID, "сообщение во втором чате"))));

        listener.poll();

        verify(conversationAgent, times(1)).processMessage(eq(CHAT_ID), anyString(), anyString());
        verify(conversationAgent, times(1)).processMessage(eq(OTHER_CHAT_ID), anyString(), anyString());
        verify(conversationAgent, times(2)).processMessage(anyLong(), anyString(), anyString());
    }
}
