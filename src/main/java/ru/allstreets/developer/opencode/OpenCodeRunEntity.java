package ru.allstreets.developer.opencode;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;
import ru.allstreets.developer.checkpoint.EntityUtil;

import java.time.Instant;
import java.util.UUID;

/**
 * Персистентное состояние одного прогона OpenCode-агента (analyst/developer/tester/
 * post_validation). Делает прогон восстанавливаемым после рестарта Spring и устойчивым
 * к обрыву TCP: агент продолжает работать в sidecar, а мы продолжаем опрос того же
 * {@code messageId}, а не отправляем промпт заново.
 * <p>
 * {@code @Id} — UUID, генерируем сами (assigned identifier) → реализован
 * {@link Persistable} с флагом {@code isNew} по образцу {@link TaskProgressEntity}.
 * Уникальный индекс {@code (task_id, agent_name, message_id)} — ключ идемпотентности:
 * {@code messageId} генерируется до отправки промпта и однозначно идентифицирует прогон.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "opencode_run",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_opencode_run_task_agent_msg",
                columnNames = {"task_id", "agent_name", "message_id"}))
public class OpenCodeRunEntity implements Persistable<String> {

    @Id
    @Column(name = "id")
    private String id;

    @Transient
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    private boolean isNew = true;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "agent_name", nullable = false)
    private String agentName;

    @Column(name = "session_id")
    private String sessionId;

    /**
     * Ключ идемпотентности — генерируем сами до отправки промпта.
     */
    @Column(name = "message_id", nullable = false)
    private String messageId;

    /**
     * Рабочая директория (worktree слота), по которой ведётся опрос.
     */
    @Column(name = "cwd")
    private String cwd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OpenCodeRunStatus status;

    /**
     * Накопленный текст агента (записывается по завершении).
     */
    @Column(name = "output", columnDefinition = "TEXT")
    private String output;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    /**
     * Подтверждено, что {@code prompt_async} принят sidecar (204).
     */
    @Column(name = "prompt_sent", nullable = false, columnDefinition = "boolean default false")
    private boolean promptSent;

    public OpenCodeRunEntity(String taskId, String agentName, String sessionId,
                             String messageId, String cwd, OpenCodeRunStatus status) {
        this.id = UUID.randomUUID().toString();
        this.taskId = taskId;
        this.agentName = agentName;
        this.sessionId = sessionId;
        this.messageId = messageId;
        this.cwd = cwd;
        this.status = status;
        this.startedAt = Instant.now();
        this.promptSent = false;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean equals(Object o) {
        return EntityUtil.equals(this, o, OpenCodeRunEntity::getId);
    }

    @Override
    public int hashCode() {
        return EntityUtil.hashCode(this);
    }
}
