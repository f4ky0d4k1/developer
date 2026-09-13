package ru.allstreets.developer.opencode;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Персистентность прогонов OpenCode-агентов. Resume-поиск — по
 * {@code (taskId, agentName)} в незавершённых статусах (STARTING/RUNNING);
 * уникальный индекс {@code (task_id, agent_name, message_id)} гарантирует, что
 * {@code messageId} однозначно идентифицирует прогон.
 */
@Repository
public interface OpenCodeRunRepository extends JpaRepository<OpenCodeRunEntity, String> {

    /**
     * Последний незавершённый прогон пары (задача, агент) — кандидат на resume после
     * рестарта: продолжаем опрос его {@code messageId} вместо отправки нового промпта.
     */
    List<OpenCodeRunEntity> findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(
            String taskId, String agentName, List<OpenCodeRunStatus> statuses);
}
