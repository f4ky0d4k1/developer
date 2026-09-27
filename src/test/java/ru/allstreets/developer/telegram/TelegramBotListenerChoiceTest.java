package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.allstreets.developer.agents.AgentResponses;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanInputRegistry;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Вопрос классификатора с вариантами → inline-кнопки; тап по кнопке ({@code choice:}) → выбор
 * возвращается в классификатор как «Выбрано: &lt;вариант&gt;», который сам распутывает контекст
 * по истории чата (без отдельного хранилища).
 */
class TelegramBotListenerChoiceTest {

    private static final long CHAT_ID = 1L;
    private static final String BOT = "AllstreetsAIbackendBot";

    private TelegramGateway telegram;
    private ConversationAgent conversationAgent;
    private ChatMemoryService chatMemory;
    private TelegramBotListener listener;

    @BeforeEach
    void setUp() {
        telegram = mock(TelegramGateway.class);
        conversationAgent = mock(ConversationAgent.class);
        chatMemory = mock(ChatMemoryService.class);
        listener = new TelegramBotListener(telegram, mock(TaskLauncher.class), conversationAgent,
                chatMemory, mock(HumanInputRegistry.class), mock(ActiveTaskRegistry.class),
                mock(TaskRepository.class), mock(ReplyAnchorRegistry.class));
        ReflectionTestUtils.setField(listener, "allowedChatIdsRaw", String.valueOf(CHAT_ID));
        ReflectionTestUtils.setField(listener, "botUsername", BOT);
        listener.init();
    }

    @Test
    void answerWithOptions_sendsKeyboard() {
        when(conversationAgent.processMessage(anyLong(), anyString(), anyString()))
                .thenReturn(new ConversationAgent.Decision(AgentResponses.FastAction.ANSWER, null,
                        "В каком репозитории?", null, List.of("owner/a", "owner/b")));

        var msg = new TelegramGateway.Message(501L,
                new TelegramGateway.User(2L, false, "Дима", "dmitry"),
                new TelegramGateway.Chat(CHAT_ID, "private"), "проверь тикет", 0L, List.of(), null);
        when(telegram.getUpdates(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TelegramGateway.TelegramUpdates(true, List.of(new TelegramGateway.Update(1, msg, null))));

        listener.poll();

        verify(telegram).sendMessageWithKeyboard(org.mockito.ArgumentMatchers.eq(CHAT_ID),
                org.mockito.ArgumentMatchers.eq("В каком репозитории?"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void choiceCallback_routesBackThroughClassifier() {
        var callback = new TelegramGateway.CallbackQuery("cb1",
                new TelegramGateway.User(2L, false, "Дима", "dmitry"),
                new TelegramGateway.Message(501L, null,
                        new TelegramGateway.Chat(CHAT_ID, "group"), "question", 0L, List.of(), null),
                "choice:owner/a");
        when(telegram.getUpdates(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TelegramGateway.TelegramUpdates(true,
                        List.of(new TelegramGateway.Update(1, null, callback))));
        when(conversationAgent.processMessage(anyLong(), anyString(), anyString()))
                .thenReturn(new ConversationAgent.Decision(AgentResponses.FastAction.ANSWER, null, "запускаю", null, null));

        listener.poll();

        verify(conversationAgent).processMessage(org.mockito.ArgumentMatchers.eq(CHAT_ID),
                org.mockito.ArgumentMatchers.eq("dmitry"),
                org.mockito.ArgumentMatchers.eq("Выбрано: owner/a"));
    }

    @Test
    void choiceKeyboard_skipsBlankOptions() {
        var keyboard = TelegramBotListener.choiceKeyboard(
                java.util.Arrays.asList("owner/a", "", null, "owner/b"));

        org.junit.jupiter.api.Assertions.assertEquals(2, keyboard.size());
        org.junit.jupiter.api.Assertions.assertEquals("choice:owner/a",
                keyboard.get(0).get(0).get("callback_data"));
        org.junit.jupiter.api.Assertions.assertEquals("choice:owner/b",
                keyboard.get(1).get(0).get("callback_data"));
    }
}
