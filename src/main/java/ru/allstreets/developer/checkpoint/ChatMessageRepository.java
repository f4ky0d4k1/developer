package ru.allstreets.developer.checkpoint;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    List<ChatMessageEntity> findByChatIdOrderByCreatedAtDesc(Long chatId, Pageable pageable);

    @Query(
            "SELECT m FROM ChatMessageEntity m WHERE m.chatId = :chatId " +
                    "AND LOWER(m.text) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                    "ORDER BY m.createdAt DESC")
    List<ChatMessageEntity> searchByKeyword(@Param("chatId") Long chatId, @Param("keyword") String keyword, Pageable pageable);
}
