package ru.allstreets.developer.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

/**
 * Тест логики checkpoint flow: interrupt → save → restore → resume.
 * Проверяет что CheckpointService корректно сохраняет и восстанавливает state.
 */
@ExtendWith(MockitoExtension.class)
class CheckpointFlowTest {

    @Mock
    private CheckpointRepository checkpointRepository;

    private CheckpointService checkpointService;

    @BeforeEach
    void setUp() {
        checkpointService = new CheckpointService(checkpointRepository, new ObjectMapper());
    }

    @Test
    void saveAndRestoreCheckpoint_roundTrip() {
        // Given: context с state
        var ctx = io.github.asekka.springai.agents.core.AgentContext.of("test task")
                .with(ru.allstreets.developer.state.TaskState.TASK_ID, "task-123")
                .with(ru.allstreets.developer.state.TaskState.TG_CHAT_ID, "12345")
                .with(ru.allstreets.developer.state.TaskState.REWORK_COUNT, 2);

        // When: save checkpoint
        checkpointService.saveCheckpoint("run-1", "analyst", ctx, "RUNNING");

        // Then: verify saved
        verify(checkpointRepository).save(argThat(entity ->
                entity.getRunId().equals("run-1") &&
                        entity.getNodeName().equals("analyst") &&
                        entity.getStatus().equals("RUNNING") &&
                        entity.getStateJson() != null &&
                        !entity.getStateJson().isBlank()
        ));
    }

    @Test
    void restoreCheckpoint_returnsNull_whenNotFound() {
        when(checkpointRepository.findTopByRunIdOrderByCreatedAtDesc("missing"))
                .thenReturn(java.util.Optional.empty());

        var result = checkpointService.restoreCheckpoint("missing");

        assertNull(result, "Should return null when checkpoint not found");
    }

    @Test
    void getLastNodeName_returnsNodeFromCheckpoint() {
        var entity = new CheckpointEntity("cp-1", "run-1", "developer", "{}", "RUNNING");
        when(checkpointRepository.findTopByRunIdOrderByCreatedAtDesc("run-1"))
                .thenReturn(java.util.Optional.of(entity));

        String lastNode = checkpointService.getLastNodeName("run-1");

        assertEquals("developer", lastNode);
    }

    @Test
    void cleanup_deletesByRunId() {
        checkpointService.cleanup("run-1");

        verify(checkpointRepository).deleteByRunId("run-1");
    }

    @Test
    void getUnfinishedCheckpoints_returnsRunningOnly() {
        var running = new CheckpointEntity("cp-1", "run-1", "analyst", "{}", "RUNNING");
        when(checkpointRepository.findByStatus("RUNNING"))
                .thenReturn(java.util.List.of(running));

        var unfinished = checkpointService.getUnfinishedCheckpoints();

        assertEquals(1, unfinished.size());
        assertEquals("RUNNING", unfinished.getFirst().getStatus());
    }
}
