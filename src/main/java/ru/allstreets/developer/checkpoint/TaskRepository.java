package ru.allstreets.developer.checkpoint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskRepository extends JpaRepository<TaskEntity, String> {

    List<TaskEntity> findByStatus(String status);

    List<TaskEntity> findByNotifyChatId(Long notifyChatId);

    Optional<TaskEntity> findByGitBranch(String gitBranch);

    List<TaskEntity> findByTaskIdStartingWith(String prefix);
}
