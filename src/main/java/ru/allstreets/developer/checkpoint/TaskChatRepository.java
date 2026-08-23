package ru.allstreets.developer.checkpoint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskChatRepository extends JpaRepository<TaskChatEntity, TaskChatId> {

    List<TaskChatEntity> findByChatId(Long chatId);

    void deleteByTaskId(String taskId);
}
