package ru.allstreets.developer.checkpoint;

import io.github.asekka.springai.agents.graph.Checkpoint;
import io.github.asekka.springai.agents.graph.CheckpointStore;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Адаптер {@link CheckpointStore} библиотеки spring-agent-flow-graph поверх
 * {@link CheckpointService}/Postgres. Библиотека сама вызывает {@code save}
 * после каждого узла и {@code delete} по завершении графа — в ручную
 * это больше не нужно оборачивать декоратором вокруг каждого агента.
 */
@Component
public class JpaCheckpointStore implements CheckpointStore {

    private final CheckpointService checkpointService;

    public JpaCheckpointStore(CheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    @Override
    public void save(Checkpoint checkpoint) {
        String interruptReason = (checkpoint.isInterrupted() && checkpoint.interrupt() != null)
                ? checkpoint.interrupt().reason() : null;
        checkpointService.saveCheckpoint(checkpoint.runId(), checkpoint.nextNode(), checkpoint.context(),
                checkpoint.iterations(), interruptReason);
    }

    @Override
    public Optional<Checkpoint> load(String runId) {
        return checkpointService.loadCheckpoint(runId);
    }

    @Override
    public void delete(String runId) {
        checkpointService.cleanup(runId);
    }
}
