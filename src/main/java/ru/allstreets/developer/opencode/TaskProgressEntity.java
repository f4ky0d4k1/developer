package ru.allstreets.developer.opencode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.allstreets.developer.checkpoint.EntityUtil;
import ru.allstreets.developer.checkpoint.TaskEntity;

/**
 * Прогресс выполнения OpenCode агента по задаче.
 * One-to-one с TaskEntity, FK на agent_tasks.task_id.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "task_progress")
public class TaskProgressEntity {

    @Id
    @Column(name = "task_id")
    private String taskId;

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
