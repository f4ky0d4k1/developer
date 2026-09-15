package ru.allstreets.developer.opencode;

import org.junit.jupiter.api.Test;
import ru.allstreets.developer.checkpoint.TaskRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TaskProgressRegistry#get} должен честно мапить таймстемпы из БД-entity в DTO.
 * Раньше {@code startTimeMs} был {@code final} и ставился {@code now} в конструкторе DTO,
 * а {@code lastUpdateMs} вообще не мапился — из-за этого {@code elapsed} в деталях задачи
 * был ВСЕГДА 0 (агент вроде работает, а бот показывает «0с»).
 */
class TaskProgressRegistryTest {

    @Test
    void get_mapsTimestampsAndCounters() {
        TaskProgressRepository repo = mock(TaskProgressRepository.class);
        TaskRepository taskRepo = mock(TaskRepository.class);
        TaskProgressRegistry registry = new TaskProgressRegistry(repo, taskRepo);

        TaskProgressEntity entity = new TaskProgressEntity("task-1", "tester");
        entity.setStartTimeMs(1_000L);
        entity.setLastUpdateMs(61_000L);
        entity.setStepCount(5);
        entity.setTotalTokens(120);
        entity.setToolCalls("read,bash");

        when(repo.findById("task-1")).thenReturn(Optional.of(entity));

        TaskProgress p = registry.get("task-1");

        assertEquals(60L, p.getElapsedSeconds(), "elapsed = (lastUpdate - start) / 1000");
        assertEquals(5, p.getStepCount());
        assertEquals(120, p.getTotalTokens());
    }
}
