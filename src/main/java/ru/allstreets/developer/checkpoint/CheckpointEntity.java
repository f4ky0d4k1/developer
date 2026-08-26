package ru.allstreets.developer.checkpoint;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Сущность checkpoint — снимок состояния агентного графа.
 * Позволяет возобновить выполнение графа после краха приложения.
 */
@Setter
@Getter
@NoArgsConstructor
@Entity
@Table(name = "agent_checkpoints")
public class CheckpointEntity {

    @Id
    @Column(name = "checkpoint_id")
    private String checkpointId;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "node_name", nullable = false)
    private String nodeName;

    @Column(name = "state_json", nullable = false, columnDefinition = "TEXT")
    private String stateJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "iterations", columnDefinition = "integer default 0")
    private int iterations = 0;

    @Column(name = "interrupt_reason")
    private String interruptReason;

    public CheckpointEntity(String checkpointId, String runId, String nodeName, String stateJson, String status) {
        this(checkpointId, runId, nodeName, stateJson, status, 0, null);
    }

    public CheckpointEntity(String checkpointId, String runId, String nodeName, String stateJson, String status,
                            int iterations, String interruptReason) {
        this.checkpointId = checkpointId;
        this.runId = runId;
        this.nodeName = nodeName;
        this.stateJson = stateJson;
        this.status = status;
        this.iterations = iterations;
        this.interruptReason = interruptReason;
        this.createdAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        return EntityUtil.equals(this, o, CheckpointEntity::getCheckpointId);
    }

    @Override
    public int hashCode() {
        return EntityUtil.hashCode(this);
    }
}
