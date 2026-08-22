package ru.allstreets.developer.checkpoint;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Сущность сообщения чата — persistent storage для ChatMemoryService.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "agent_chat_messages")
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ChatMessageEntity(Long chatId, String role, String text, String taskId) {
        this.chatId = chatId;
        this.role = role;
        this.text = text;
        this.taskId = taskId;
        this.createdAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        return EntityUtil.equals(this, o, ChatMessageEntity::getId);
    }

    @Override
    public int hashCode() {
        return EntityUtil.hashCode(this);
    }
}
