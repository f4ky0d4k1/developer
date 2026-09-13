package ru.allstreets.developer.opencode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import ru.allstreets.developer.PostgresTestBase;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Персистентность прогона OpenCode: save/find, resume-выборка последнего
 * незавершённого прогона и уникальность ключа идемпотентности
 * {@code (task_id, agent_name, message_id)}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OpenCodeRunRepositoryTest extends PostgresTestBase {

    @Autowired
    private OpenCodeRunRepository repo;

    @Test
    void saveAndFindById() {
        var run = new OpenCodeRunEntity("task-1", "analyst", "ses_1", "msg-1", "/work/slot-0",
                OpenCodeRunStatus.STARTING);
        repo.saveAndFlush(run);

        assertNotNull(run.getId());

        var found = repo.findById(run.getId());

        assertTrue(found.isPresent());
        assertEquals("task-1", found.get().getTaskId());
        assertEquals("analyst", found.get().getAgentName());
        assertEquals("msg-1", found.get().getMessageId());
        assertEquals(OpenCodeRunStatus.STARTING, found.get().getStatus());
        assertNotNull(found.get().getStartedAt());
    }

    @Test
    void resumeQuery_returnsLatestRunningOrStarting() {
        var running = new OpenCodeRunEntity("task-1", "analyst", "ses_1", "msg-1", "/work/slot-0",
                OpenCodeRunStatus.RUNNING);
        running.setPromptSent(true);
        var done = new OpenCodeRunEntity("task-1", "analyst", "ses_2", "msg-2", "/work/slot-0",
                OpenCodeRunStatus.DONE);
        done.setPromptSent(true);
        repo.saveAllAndFlush(List.of(running, done));

        var resumable = repo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(
                "task-1", "analyst", List.of(OpenCodeRunStatus.RUNNING, OpenCodeRunStatus.STARTING));

        assertEquals(1, resumable.size());
        assertEquals("msg-1", resumable.getFirst().getMessageId());
    }

    @Test
    void resumeQuery_excludesFinished() {
        var done = new OpenCodeRunEntity("task-1", "analyst", "ses_1", "msg-1", "/work/slot-0",
                OpenCodeRunStatus.DONE);
        repo.saveAndFlush(done);

        var resumable = repo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(
                "task-1", "analyst", List.of(OpenCodeRunStatus.RUNNING, OpenCodeRunStatus.STARTING));

        assertTrue(resumable.isEmpty());
    }

    @Test
    void uniqueIndex_rejectsDuplicateTaskAgentMessage() {
        repo.saveAndFlush(new OpenCodeRunEntity("task-1", "analyst", "ses_1", "msg-1", "/work/slot-0",
                OpenCodeRunStatus.STARTING));

        var duplicate = new OpenCodeRunEntity("task-1", "analyst", "ses_2", "msg-1", "/work/slot-1",
                OpenCodeRunStatus.STARTING);

        assertThrows(DataIntegrityViolationException.class, () -> repo.saveAndFlush(duplicate));
    }

    @Test
    void find_unknown_returnsEmpty() {
        assertTrue(repo.findById("no-such-id").isEmpty());
    }
}
