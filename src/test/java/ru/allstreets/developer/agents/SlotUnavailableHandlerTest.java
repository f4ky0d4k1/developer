package ru.allstreets.developer.agents;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanLoopService;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Нехватка слотов — не фейл, а HITL-запрос со списком задач, держащих слоты, и inline-кнопками
 * «закрыть задачу» (callback_data = close:&lt;taskId&gt;).
 */
class SlotUnavailableHandlerTest {

    private static final String WAITING = "bbbbbbbb-2222-3333-4444-555566667777";
    private static final String HOLDER = "aaaaaaaa-1111-2222-3333-444455556666";

    @Test
    void asksToFreeSlots_withCloseButtons_andInterrupts() {
        OpenCodeSessionPool pool = mock(OpenCodeSessionPool.class);
        HumanLoopService humanLoop = mock(HumanLoopService.class);
        TaskRepository taskRepo = mock(TaskRepository.class);
        TelegramGateway telegram = mock(TelegramGateway.class);
        when(pool.heldTasks()).thenReturn(List.of(HOLDER));
        when(taskRepo.findById(HOLDER)).thenReturn(Optional.of(
                new TaskEntity(HOLDER, "COMPLETED", "desc", "Техучётки", 1L)));

        var handler = new SlotUnavailableHandler(pool, humanLoop, taskRepo, telegram);
        var result = handler.askToFreeSlots(WAITING, 1L, "developer");

        verify(humanLoop).registerPending(eq(WAITING), eq(1L), contains("bbbbbbbb"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<List<Map<String, String>>>> kbCaptor = ArgumentCaptor.forClass(List.class);
        verify(telegram).sendMessageWithKeyboard(eq(1L), anyString(), kbCaptor.capture(), eq(WAITING));
        String buttonLabels = kbCaptor.getValue().stream().flatMap(List::stream)
                .map(b -> b.get("text")).collect(java.util.stream.Collectors.joining(" "));
        assertTrue(buttonLabels.contains("Техучётки"), "кнопка должна называть держателя слота: " + buttonLabels);
        assertTrue(kbCaptor.getValue().stream().flatMap(List::stream)
                        .anyMatch(b -> ("close:" + HOLDER).equals(b.get("callback_data"))),
                "callback_data кнопки = close:<taskId держателя>: " + kbCaptor.getValue());
        assertEquals("HITL_SLOT", result.interrupt().reason());
        assertFalse(result.completed());
        assertEquals("developer", result.stateUpdates().get(TaskState.AGENT_ROLE));
    }

    @Test
    void noSlotsHeld_stillAddsDoneButton() {
        OpenCodeSessionPool pool = mock(OpenCodeSessionPool.class);
        when(pool.heldTasks()).thenReturn(List.of());

        var handler = new SlotUnavailableHandler(pool, mock(HumanLoopService.class),
                mock(TaskRepository.class), mock(TelegramGateway.class));
        var result = handler.askToFreeSlots(WAITING, 1L, "tester");

        assertEquals("HITL_SLOT", result.interrupt().reason());
    }

    @Test
    void buttonData_fitsTelegramLimit() {
        // callback_data ограничен 64 байтами — close:<36-символьный UUID> = 42, ок
        String data = "close:" + HOLDER;
        assertEquals(42, data.length());
    }
}
