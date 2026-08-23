package ru.allstreets.developer.opencode;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskProgressRepository extends JpaRepository<TaskProgressEntity, String> {
}
