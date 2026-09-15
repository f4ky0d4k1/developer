package ru.allstreets.developer.github;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Факт обработки комментария PR. Нужен, чтобы после рестарта приложения (и при нескольких
 * инстансах) один и тот же комментарий не запускал задачу-доработку повторно: раньше
 * множество обработанных id жило только в памяти {@code PrCommentMonitor} и терялось.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "agent_processed_pr_comments")
public class ProcessedPrCommentEntity {

    /**
     * ID комментария GitHub (issue/review comment) — он глобально уникален.
     */
    @Id
    @Column(name = "comment_id")
    private Long commentId;

    @Column(name = "pr_number", nullable = false)
    private int prNumber;

    @Column(name = "repo", nullable = false, length = 200)
    private String repo;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedPrCommentEntity(Long commentId, int prNumber, String repo) {
        this.commentId = commentId;
        this.prNumber = prNumber;
        this.repo = repo;
        this.processedAt = Instant.now();
    }
}
