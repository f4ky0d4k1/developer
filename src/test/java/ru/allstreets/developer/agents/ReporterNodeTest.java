package ru.allstreets.developer.agents;

import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Репортёр — единственный, кто пишет текст в Трекер: узел зовёт агента {@code reporter},
 * слот берётся как у остальных узлов, ошибка агента — fail, а не тихий успех.
 */
class ReporterNodeTest {

    private OpenCodeClient openCode;
    private OpenCodeSessionPool sessionPool;
    private SlotUnavailableHandler slotHandler;
    private ReporterNode node;

    @BeforeEach
    void setUp() {
        openCode = mock(OpenCodeClient.class);
        sessionPool = mock(OpenCodeSessionPool.class);
        slotHandler = mock(SlotUnavailableHandler.class);
        node = new ReporterNode(openCode, sessionPool, mock(TelegramGateway.class),
                mock(TaskRepository.class), slotHandler);
        when(sessionPool.acquireForTask(anyString(), anyString(), anyLong())).thenReturn(0);
        when(sessionPool.getSlotWorkDir(0)).thenReturn("/work/slot-0");
    }

    private AgentContext baseCtx() {
        return AgentContext.of("задача")
                .with(TaskState.TG_CHAT_ID, "1")
                .with(TaskState.TASK_ID, "task-1")
                .with(TaskState.SPEC, "Оформи отчёт в Трекере")
                .with(TaskState.TRACKER_ISSUE, "BACKEND-437")
                .with(TaskState.TARGET_REPO, "owner/repo");
    }

    @Test
    void reporterRun_callsReporterAgent_andCompletes() {
        when(openCode.runAgent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new OpenCodeClient.OpenCodeResult("success", "готово", null, null, List.of(), null, "ses_1"));

        AgentResult result = node.execute(baseCtx());

        assertFalse(result.hasError());
        assertEquals("reporter", result.stateUpdates().get(TaskState.AGENT_ROLE));
        verify(openCode).runAgent(eq("reporter"), anyString(), eq("/work/slot-0"), eq("task-1"));
    }

    @Test
    void reporterError_fails() {
        when(openCode.runAgent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new OpenCodeClient.OpenCodeResult("error", "", null, null, List.of(), "boom", "ses_1"));

        assertTrue(node.execute(baseCtx()).hasError());
    }

    @Test
    void noFreeSlot_asksHuman() {
        when(sessionPool.acquireForTask(anyString(), anyString(), anyLong())).thenReturn(-1);

        node.execute(baseCtx());

        verify(slotHandler).askToFreeSlots("task-1", 1L, "reporter");
    }

    @Test
    void noChatContext_skipsWithoutCallingAgent() {
        AgentContext ctx = AgentContext.of("задача").with(TaskState.TASK_ID, "task-1");

        AgentResult result = node.execute(ctx);

        assertFalse(result.hasError());
        verifyNoInteractions(openCode);
    }
}
