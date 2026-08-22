package ru.allstreets.developer.checkpoint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface CheckpointRepository extends JpaRepository<CheckpointEntity, String> {

    /**
     * Найти последний checkpoint для указанного runId.
     */
    Optional<CheckpointEntity> findTopByRunIdOrderByCreatedAtDesc(String runId);

    /**
     * Все checkpoint для указанного runId, отсортированные по времени.
     */
    List<CheckpointEntity> findByRunIdOrderByCreatedAtAsc(String runId);

    /**
     * Удалить все checkpoint для указанного runId (после успешного завершения).
     */
    void deleteByRunId(String runId);

    /**
     * Найти все незавершённые checkpoint (status = RUNNING).
     */
    List<CheckpointEntity> findByStatus(String status);

    void deleteByCreatedAtBefore(Instant cutoff);
}
