package ru.allstreets.developer.opencode;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TaskProgressRepository extends JpaRepository<TaskProgressEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "from TaskProgressEntity e where e.taskId = :taskId")
    Optional<TaskProgressEntity> findByIdForUpdate(@Param("taskId") String taskId);
}
