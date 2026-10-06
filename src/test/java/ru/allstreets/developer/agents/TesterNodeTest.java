package ru.allstreets.developer.agents;

import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.opencode.OpenCodeClient;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Тестировщик должен писать тесты в ветку ЗАДАЧИ (как разработчик): явную из контекста либо
 * по тикету Трекера, и прокидывать её дальше. Раньше ветка была захардкожена "feature/new-task"
 * и не совпадала с веткой разработчика — тесты и код уходили в разные ветки (инцидент ee10af92).
 */
class TesterNodeTest {

    private OpenCodeClient openCode;
    private TesterNode node;

    @BeforeEach
    void setUp() {
        openCode = mock(OpenCodeClient.class);
        OpenCodeSessionPool sessionPool = mock(OpenCodeSessionPool.class);
        node = new TesterNode(openCode, sessionPool, mock(TelegramGateway.class),
                mock(TaskRepository.class), mock(SlotUnavailableHandler.class));
        when(sessionPool.acquireForTask(anyString(), anyString(), anyLong())).thenReturn(0);
        when(sessionPool.getSlotWorkDir(0)).thenReturn("/work/slot-0");
        when(openCode.runAgent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new OpenCodeClient.OpenCodeResult("success", "тесты", null, null, List.of(), null, "ses_1"));
    }

    private AgentContext baseCtx() {
        return AgentContext.of("задача")
                .with(TaskState.TG_CHAT_ID, "1")
                .with(TaskState.TASK_ID, "task-1")
                .with(TaskState.SPEC, "ТЗ")
                .with(TaskState.TARGET_REPO, "owner/repo");
    }

    private String capturedPrompt() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(openCode).runAgent(eq("tester"), captor.capture(), anyString(), anyString());
        return captor.getValue();
    }

    @Test
    void explicitBranch_usedAndPropagated() {
        AgentResult result = node.execute(baseCtx().with(TaskState.GIT_BRANCH, "feature/BACKEND-458"));

        assertFalse(result.hasError());
        assertEquals("feature/BACKEND-458", result.stateUpdates().get(TaskState.GIT_BRANCH),
                "тестировщик обязан прокинуть ветку дальше, иначе developer выведет другую");
        assertTrue(capturedPrompt().contains("git checkout -b feature/BACKEND-458"));
    }

    @Test
    void branchDerivedFromTrackerIssue_whenNotInContext() {
        AgentResult result = node.execute(baseCtx().with(TaskState.TRACKER_ISSUE, "BACKEND-458"));

        assertEquals("feature/BACKEND-458", result.stateUpdates().get(TaskState.GIT_BRANCH));
        assertTrue(capturedPrompt().contains("git checkout -b feature/BACKEND-458"));
    }

    @Test
    void noBranchNoTracker_neverHardcodesNewTask() {
        AgentResult result = node.execute(baseCtx());

        String branch = (String) result.stateUpdates().get(TaskState.GIT_BRANCH);
        assertTrue(branch.startsWith("feature/"));
        assertFalse("feature/new-task".equals(branch),
                "ветка не должна быть захардкожена: " + branch);
    }
}
