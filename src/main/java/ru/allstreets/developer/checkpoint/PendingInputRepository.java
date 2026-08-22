package ru.allstreets.developer.checkpoint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PendingInputRepository extends JpaRepository<PendingInputEntity, String> {

}
