package ru.allstreets.developer.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.checkpoint.ChatMessageRepository;
import ru.allstreets.developer.checkpoint.CheckpointRepository;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskLockService;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.opencode.TaskProgressRegistry;
import ru.allstreets.developer.telegram.ActiveTaskRegistry;
import ru.allstreets.developer.telegram.ChatTitleResolver;
import ru.allstreets.developer.telegram.TaskLauncher;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code resetSlot} не должен сносить worktree РАБОТАЮЩЕЙ задачи: агент в этот момент в нём работает,
 * а следом cancel сносит checkpoint — задача остаётся в разломанном состоянии (инцидент 5d8aabf5).
 */
class TaskMcpToolsResetSlotTest {

    private static final String FULL = "5d8aabf5-cf43-4a59-a672-ae3d0e686437";
    private static final String PARTIAL = "5d8aabf5";

    private TaskLauncher taskLauncher;
    private TaskRepository taskRepo;
    private TaskMcpTools tools;

    @BeforeEach
    void setUp() {
        ActiveTaskRegistry taskRegistry = mock(ActiveTaskRegistry.class);
        taskRepo = mock(TaskRepository.class);
        taskLauncher = mock(TaskLauncher.class);

        TaskEntity task = new TaskEntity(FULL, "FAILED", "d", "t", 42L);
        when(taskRepo.findById(PARTIAL)).thenReturn(Optional.empty());
        when(taskRepo.findByTaskIdStartingWith(PARTIAL)).thenReturn(List.of(task));

        tools = new TaskMcpTools(
                taskRegistry, taskRepo,
                mock(CheckpointRepository.class), mock(TaskLockService.class),
                taskLauncher, mock(HumanInputRegistry.class),
                mock(TaskProgressRegistry.class), mock(ChatMessageRepository.class),
                mock(ChatTitleResolver.class),
                "dima");
    }

    @Test
    void runningTask_isNotReset() {
        when(taskLauncher.isRunning(FULL)).thenReturn(true);

        String result = tools.resetSlot(PARTIAL, null);

        assertTrue(result.contains("RUNNING"), result);
        verify(taskLauncher, never()).resetSlot(anyString());
    }

    @Test
    void notRunningTask_isReset() {
        when(taskLauncher.isRunning(FULL)).thenReturn(false);
        when(taskLauncher.resetSlot(FULL)).thenReturn(true);

        String result = tools.resetSlot(PARTIAL, null);

        assertTrue(result.contains("reset"), result);
        verify(taskLauncher).resetSlot(FULL);
    }

    @Test
    void unknownTask_isNotFound() {
        when(taskRepo.findByTaskIdStartingWith("deadbeef")).thenReturn(List.of());

        String result = tools.resetSlot("deadbeef", null);

        assertTrue(result.contains("not found"), result);
        verify(taskLauncher, never()).resetSlot(anyString());
    }
}
