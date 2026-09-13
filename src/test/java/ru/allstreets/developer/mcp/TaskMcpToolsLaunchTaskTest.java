package ru.allstreets.developer.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import ru.allstreets.developer.checkpoint.ChatMessageRepository;
import ru.allstreets.developer.checkpoint.CheckpointRepository;
import ru.allstreets.developer.checkpoint.CheckpointService;
import ru.allstreets.developer.checkpoint.TaskLockService;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.opencode.TaskProgressRegistry;
import ru.allstreets.developer.telegram.ActiveTaskRegistry;
import ru.allstreets.developer.telegram.TaskLauncher;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Границы инструмента {@code launch_task}:
 * <ul>
 *   <li>запускать может только trigger-user (username — из ToolContext, не от модели);</li>
 *   <li>без репозитория/описания запуска нет (repo required на уровне схемы + защита от пустой строки).</li>
 * </ul>
 */
class TaskMcpToolsLaunchTaskTest {

    private TaskLauncher taskLauncher;
    private TaskMcpTools tools;

    @BeforeEach
    void setUp() {
        ActiveTaskRegistry taskRegistry = mock(ActiveTaskRegistry.class);
        when(taskRegistry.getActiveTasks(anyLong())).thenReturn(Map.of());
        taskLauncher = mock(TaskLauncher.class);
        tools = new TaskMcpTools(
                taskRegistry,
                mock(TaskRepository.class),
                mock(CheckpointRepository.class),
                mock(CheckpointService.class),
                mock(TaskLockService.class),
                taskLauncher,
                mock(HumanInputRegistry.class),
                mock(TaskProgressRegistry.class),
                mock(ChatMessageRepository.class),
                "dima");
    }

    private static ToolContext ctx(String username) {
        return new ToolContext(Map.of("username", username));
    }

    @Test
    void launchTask_blankRepo_returnsErrorAndDoesNotLaunch() {
        String result = tools.launchTask(1L, "   ", "сделай X", null, ctx("dima"));

        assertTrue(result.contains("ERROR"), result);
        verify(taskLauncher, never()).launch(anyString(), anyLong(), any(), any());
    }

    @Test
    void launchTask_blankDescription_returnsErrorAndDoesNotLaunch() {
        String result = tools.launchTask(1L, "allstreets/backend", "  ", null, ctx("dima"));

        assertTrue(result.contains("ERROR"), result);
        verify(taskLauncher, never()).launch(anyString(), anyLong(), any(), any());
    }

    @Test
    void launchTask_nonTriggerUser_isDenied() {
        String result = tools.launchTask(1L, "allstreets/backend", "сделай X", null, ctx("stranger"));

        assertTrue(result.contains("not allowed"), result);
        verify(taskLauncher, never()).launch(anyString(), anyLong(), any(), any());
    }

    @Test
    void launchTask_triggerUser_normalizesRepoAndLaunches() {
        tools.launchTask(1L, "  AllStreets/Backend ", "сделай X", null, ctx("DiMa"));

        verify(taskLauncher).launch(eq("сделай X"), eq(1L), eq("allstreets/backend"), org.mockito.ArgumentMatchers.isNull());
    }
}
