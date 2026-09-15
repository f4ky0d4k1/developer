package ru.allstreets.developer.github;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Хранилище обработанных PR-комментариев ({@link ProcessedPrCommentEntity}) — persistent
 * дедуп для {@link PrCommentMonitor} между рестартами.
 */
@Repository
public interface ProcessedPrCommentRepository extends JpaRepository<ProcessedPrCommentEntity, Long> {
}
