package ru.allstreets.developer.mcp;

import org.junit.jupiter.api.Test;
import ru.allstreets.developer.checkpoint.ChatMessageEntity;
import ru.allstreets.developer.checkpoint.ChatMessageRepository;
import ru.allstreets.developer.checkpoint.CheckpointRepository;
import ru.allstreets.developer.checkpoint.CheckpointService;
import ru.allstreets.developer.checkpoint.TaskLockService;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.opencode.TaskProgressRegistry;
import ru.allstreets.developer.telegram.ActiveTaskRegistry;
import ru.allstreets.developer.telegram.TaskLauncher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code getChatHistory} даёт оркестратору читать историю чата глубже окна: видно роль,
 * привязку к задаче и текст — чтобы поднимать прошлые задачи/уточнения и цепочки ретраев.
 */
class TaskMcpToolsHistoryTest {

    @Test
    void getChatHistory_formatsRoleTaskAndText() {
        ChatMessageRepository repo = mock(ChatMessageRepository.class);
        ChatMessageEntity user = new ChatMessageEntity(5L, "user", "перезапусти задачу", null);
        ChatMessageEntity bot = new ChatMessageEntity(5L, "bot", "запускаю заново", "abcdef12-3456-7890-abcd-ef1234567890");
        when(repo.findByChatIdOrderByCreatedAtDesc(eq(5L), any())).thenReturn(List.of(bot, user));

        TaskMcpTools tools = new TaskMcpTools(
                mock(ActiveTaskRegistry.class),
                mock(TaskRepository.class),
                mock(CheckpointRepository.class),
                mock(TaskLockService.class),
                mock(TaskLauncher.class),
                mock(HumanInputRegistry.class),
                mock(TaskProgressRegistry.class),
                repo,
                "dima");

        String out = tools.getChatHistory(5L, 20);

        assertTrue(out.contains("user: перезапусти задачу"), out);
        assertTrue(out.contains("[task:abcdef12]"), "должна быть привязка к задаче: " + out);
        assertTrue(out.contains("bot"), out);
        assertTrue(out.contains("запускаю заново"), out);
    }
}
