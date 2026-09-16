package ru.allstreets.developer.checkpoint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PendingInputRepository extends JpaRepository<PendingInputEntity, String> {

    List<PendingInputEntity> findByChatId(Long chatId);
}
