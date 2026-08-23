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
 * Сущность задачи — persistent storage для ActiveTaskRegistry.
 * Связывает taskId с чатами (many-to-many через TaskChatEntity).
 * notify_chat_id — чат для уведомлений (по умолчанию первый).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "agent_tasks")
public class TaskEntity {

    @Id
    @Column(name = "task_id")
    private String taskId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "notify_chat_id")
    private Long notifyChatId;

    @Column(name = "git_branch")
    private String gitBranch;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "analysis_done", nullable = false, columnDefinition = "boolean default false")
    private boolean analysisDone;

    @Column(name = "requires_development", nullable = false, columnDefinition = "boolean default false")
    private boolean requiresDevelopment;

    @Column(name = "development_done", nullable = false, columnDefinition = "boolean default false")
    private boolean developmentDone;

    @Column(name = "pr_created", nullable = false, columnDefinition = "boolean default false")
    private boolean prCreated;

    @Column(name = "requires_testing", nullable = false, columnDefinition = "boolean default false")
    private boolean requiresTesting;

    @Column(name = "tests_written", nullable = false, columnDefinition = "boolean default false")
    private boolean testsWritten;

    @Column(name = "testing_done", nullable = false, columnDefinition = "boolean default false")
    private boolean testingDone;

    @Column(name = "deleted", nullable = false, columnDefinition = "boolean default false")
    private boolean deleted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public TaskEntity(String taskId, String status, String description, String title, Long notifyChatId) {
        this.taskId = taskId;
        this.status = status;
        this.description = description;
        this.title = title;
        this.notifyChatId = notifyChatId;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        return EntityUtil.equals(this, o, TaskEntity::getTaskId);
    }

    @Override
    public int hashCode() {
        return EntityUtil.hashCode(this);
    }
}
