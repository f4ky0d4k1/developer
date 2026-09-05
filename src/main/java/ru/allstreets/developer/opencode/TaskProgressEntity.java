package ru.allstreets.developer.opencode;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;
import ru.allstreets.developer.checkpoint.EntityUtil;
import ru.allstreets.developer.checkpoint.TaskEntity;

/**
 * Прогресс выполнения OpenCode агента по задаче.
 * One-to-one с TaskEntity, FK на agent_tasks.task_id.
 * <p>
 * Реализует {@link Persistable} с явным флагом {@code isNew}: id здесь —
 * derived identifier через {@code @MapsId} и проставляется уже в конструкторе,
 * поэтому стандартная эвристика Spring Data ({@code isNew() == (id == null)})
 * всегда считает новую сущность "уже существующей" и вызывает {@code merge()}
 * вместо {@code persist()}. Hibernate в этом случае может выполнить UPDATE по
 * несуществующей строке и упасть с {@code ObjectOptimisticLockingFailureException}
 * ({@code (or unsaved-value mapping was incorrect)}) вместо обычного INSERT.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "task_progress")
public class TaskProgressEntity implements Persistable<String> {

    @Id
    @Column(name = "task_id")
    private String taskId;

    @Transient
    @Getter(lombok.AccessLevel.NONE)
    private boolean isNew = false;

    @OneToOne
    @MapsId
    @JoinColumn(name = "task_id")
    private TaskEntity task;

    @Column(name = "agent_name", nullable = false)
    private String agentName;

    @Column(name = "current_tool")
    private String currentTool;

    @Column(name = "tool_calls", columnDefinition = "TEXT")
    private String toolCalls;

    @Column(name = "recent_events", columnDefinition = "TEXT")
    private String recentEvents;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "cost", nullable = false)
    private double cost;

    @Column(name = "step_count", nullable = false)
    private int stepCount;

    @Column(name = "last_text", columnDefinition = "TEXT")
    private String lastText;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "start_time_ms", nullable = false)
    private long startTimeMs;

    @Column(name = "last_update_ms", nullable = false)
    private long lastUpdateMs;

    @Column(name = "finished", nullable = false, columnDefinition = "boolean default false")
    private boolean finished;

    public TaskProgressEntity(TaskEntity task, String agentName) {
        this.task = task;
        this.taskId = task.getTaskId();
        this.agentName = agentName;
        this.startTimeMs = System.currentTimeMillis();
        this.lastUpdateMs = startTimeMs;
        this.toolCalls = "";
        this.recentEvents = "";
        this.isNew = true;
    }

    @Override
    public String getId() {
        return taskId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public boolean equals(Object o) {
        return EntityUtil.equals(this, o, TaskProgressEntity::getTaskId);
    }

    @Override
    public int hashCode() {
        return EntityUtil.hashCode(this);
    }
}
