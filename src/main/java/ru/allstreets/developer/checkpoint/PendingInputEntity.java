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
 * Сущность pending HITL-вопроса — persistent storage для HumanInputRegistry.
 * При рестарте приложения позволяет ре-нотифицировать пользователя
 * о незакрытых вопросах от агентов.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "agent_pending_inputs")
public class PendingInputEntity {

    @Id
    @Column(name = "task_id")
    private String taskId;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public PendingInputEntity(String taskId, Long chatId, String question) {
        this.taskId = taskId;
        this.chatId = chatId;
        this.question = question;
        this.createdAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        return EntityUtil.equals(this, o, PendingInputEntity::getTaskId);
    }

    @Override
    public int hashCode() {
        return EntityUtil.hashCode(this);
    }

}
