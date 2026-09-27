package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanInputRegistry;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Роутинг входящих сообщений по паре {@code (chat_id, message_thread_id)} (BACKEND-443, T5).
 * <p>
 * Сообщение из forum-темы задачи адресуется корню этой темы, ответ уходит обратно В ТУ ЖЕ
 * тему ({@code message_thread_id}). Если тема неизвестна, принадлежит другому чату или
 * БД недоступна — ответ идёт в General без {@code message_thread_id} (fail-open, утечки
 * чужого контекста нет).
 */
class TelegramBotListenerTopicRoutingTest {

    private static final long CHAT = -100123L;
    private static final long OTHER_CHAT = -100999L;
    private static final long FOREIGN_CHAT = -100555L;
    private static final long THREAD = 77L;

    private TelegramGateway telegram;
    private TaskRepository taskRepo;
    private TelegramBotListener listener;

    @BeforeEach
    void setUp() {
        telegram = mock(TelegramGateway.class);
        taskRepo = mock(TaskRepository.class);
        listener = new TelegramBotListener(telegram, mock(TaskLauncher.class),
                mock(ConversationAgent.class), mock(ChatMemoryService.class),
                mock(HumanInputRegistry.class), mock(ActiveTaskRegistry.class),
                taskRepo, new ReplyAnchorRegistry());
        ReflectionTestUtils.setField(listener, "pollingTimeout", 0);
        ReflectionTestUtils.setField(listener, "allowedChatIdsRaw", CHAT + "," + OTHER_CHAT);
        ReflectionTestUtils.setField(listener, "mentionFreeChatIdsRaw", CHAT + "," + OTHER_CHAT);
        ReflectionTestUtils.setField(listener, "botUsername", "testbot");
        listener.init();
    }

    private static TelegramGateway.Message message(long chatId, String text, Integer threadId) {
        return new TelegramGateway.Message(5L,
                new TelegramGateway.User(1L, false, "Дима", "dmitry"),
                new TelegramGateway.Chat(chatId, "supergroup", true),
                text, 0L, List.of(), null, threadId);
    }

    private void stubUpdate(TelegramGateway.Message msg) {
        when(telegram.getUpdates(anyInt(), anyInt()))
                .thenReturn(new TelegramGateway.TelegramUpdates(true,
                        List.of(new TelegramGateway.Update(1, msg, null))));
    }

    private static TaskEntity rootTask(String taskId) {
        TaskEntity task = new TaskEntity(taskId, "RUNNING", "d", "Тема", CHAT);
        task.setThreadId(THREAD);
        task.setRootThreadId(THREAD);
        return task;
    }

    @Test
    void threadMessage_routesToRootTask_andRepliesIntoThatThread() {
        stubUpdate(message(CHAT, "/status", (int) THREAD));
        when(taskRepo.findByNotifyChatIdAndThreadIdAndParentTaskIdIsNull(CHAT, THREAD))
                .thenReturn(Optional.of(rootTask("root-1")));

        listener.poll();

        verify(telegram).sendMessage(eq(CHAT), anyString(), eq("root-1"));
    }

    @Test
    void unknownThread_repliesWithoutThread_noContextLeak() {
        stubUpdate(message(CHAT, "/status", (int) THREAD));
        when(taskRepo.findByNotifyChatIdAndThreadIdAndParentTaskIdIsNull(CHAT, THREAD))
                .thenReturn(Optional.empty());

        listener.poll();

        verify(telegram).sendMessage(eq(CHAT), anyString());
        verify(telegram, never()).sendMessage(anyLong(), anyString(), anyString());
    }

    @Test
    void generalMessage_repliesWithoutThread() {
        stubUpdate(message(CHAT, "/status", null));

        listener.poll();

        verify(telegram).sendMessage(eq(CHAT), anyString());
        verify(telegram, never()).sendMessage(anyLong(), anyString(), anyString());
        verify(taskRepo, never())
                .findByNotifyChatIdAndThreadIdAndParentTaskIdIsNull(anyLong(), anyLong());
    }

    @Test
    void sameThreadInAnotherChat_isNotRoutedToFirstChatsTask() {
        // Тема принадлежит notify-чату задачи; в другом чате тот же номер темы — чужой.
        stubUpdate(message(OTHER_CHAT, "/status", (int) THREAD));
        when(taskRepo.findByNotifyChatIdAndThreadIdAndParentTaskIdIsNull(CHAT, THREAD))
                .thenReturn(Optional.of(rootTask("root-1")));

        listener.poll();

        verify(taskRepo).findByNotifyChatIdAndThreadIdAndParentTaskIdIsNull(OTHER_CHAT, THREAD);
        verify(taskRepo, never()).findByNotifyChatIdAndThreadIdAndParentTaskIdIsNull(CHAT, THREAD);
        verify(telegram).sendMessage(eq(OTHER_CHAT), anyString());
        verify(telegram, never()).sendMessage(eq(OTHER_CHAT), anyString(), anyString());
    }

    @Test
    void repositoryFailure_failsOpenToGeneral() {
        stubUpdate(message(CHAT, "/status", (int) THREAD));
        when(taskRepo.findByNotifyChatIdAndThreadIdAndParentTaskIdIsNull(CHAT, THREAD))
                .thenThrow(new RuntimeException("db down"));

        listener.poll();

        verify(telegram).sendMessage(eq(CHAT), anyString());
        verify(telegram, never()).sendMessage(anyLong(), anyString(), anyString());
    }

    @Test
    void disallowedChat_isIgnoredEntirely() {
        stubUpdate(message(FOREIGN_CHAT, "/status", (int) THREAD));
        when(taskRepo.findByNotifyChatIdAndThreadIdAndParentTaskIdIsNull(FOREIGN_CHAT, THREAD))
                .thenReturn(Optional.of(rootTask("root-1")));

        listener.poll();

        verify(telegram, never()).sendMessage(anyLong(), anyString());
        verify(telegram, never()).sendMessage(anyLong(), anyString(), anyString());
    }
}
