package ru.allstreets.developer.checkpoint;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Связь task ↔ chat (many-to-many).
 * Одна задача может обсуждаться в нескольких чатах.
 */
@Setter
@Getter
@NoArgsConstructor
@Entity
@Table(name = "agent_task_chats")
@IdClass(TaskChatId.class)
public class TaskChatEntity {

    @Id
    @Column(name = "task_id")
    private String taskId;

    @Id
    @Column(name = "chat_id")
    private Long chatId;

    public TaskChatEntity(String taskId, Long chatId) {
        this.taskId = taskId;
        this.chatId = chatId;
    }

    @Override
    public boolean equals(Object o) {
        return EntityUtil.equals(this, o, e -> e.getTaskId() + ":" + e.getChatId());
    }

    @Override
    public int hashCode() {
        return EntityUtil.hashCode(this);
    }
}
