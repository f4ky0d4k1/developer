package ru.allstreets.developer.agents;

import org.junit.jupiter.api.Test;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanLoopService;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.state.TaskState;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Нехватка слотов — не фейл, а HITL-запрос со списком задач, держащих слоты.
 */
class SlotUnavailableHandlerTest {

    private static final String WAITING = "bbbbbbbb-2222-3333-4444-555566667777";
    private static final String HOLDER = "aaaaaaaa-1111-2222-3333-444455556666";

    @Test
    void asksToFreeSlots_andInterrupts() {
        OpenCodeSessionPool pool = mock(OpenCodeSessionPool.class);
        HumanLoopService humanLoop = mock(HumanLoopService.class);
        TaskRepository taskRepo = mock(TaskRepository.class);
        when(pool.heldTasks()).thenReturn(List.of(HOLDER));
        when(taskRepo.findById(HOLDER)).thenReturn(Optional.of(
                new TaskEntity(HOLDER, "COMPLETED", "desc", "Техучётки", 1L)));

        var handler = new SlotUnavailableHandler(pool, humanLoop, taskRepo);
        var result = handler.askToFreeSlots(WAITING, 1L, "developer");

        verify(humanLoop).askHuman(eq(WAITING), eq(1L), contains("Техучётки"));
        assertEquals("HITL_SLOT", result.interrupt().reason());
        assertFalse(result.completed());
        assertEquals("developer", result.stateUpdates().get(TaskState.AGENT_ROLE));
    }
}
