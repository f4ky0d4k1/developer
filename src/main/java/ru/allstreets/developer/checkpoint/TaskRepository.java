package ru.allstreets.developer.checkpoint;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskRepository extends JpaRepository<TaskEntity, String> {

    List<TaskEntity> findByStatus(String status);

    List<TaskEntity> findByNotifyChatId(Long notifyChatId);

    Optional<TaskEntity> findByGitBranch(String gitBranch);

    List<TaskEntity> findByTaskIdStartingWith(String prefix);

    /**
     * Страница задач чата (не удалённых), свежие первыми. Возвращает {@code Page} —
     * вместе с {@code totalElements} для «+N ещё». Заменяет прежнюю выборку всех задач
     * чата с последующим N+1 по {@code findById}.
     */
    Page<TaskEntity> findByNotifyChatIdAndDeletedFalseOrderByCreatedAtDesc(Long notifyChatId, Pageable pageable);

    /**
     * Проекты чата для памяти оркестратора: уникальные репозитории (нормализованные
     * LOWER/TRIM) с числом задач и последней задачей, по убыванию свежести, не более
     * {@code limit} строк. Агрегация целиком на стороне БД — на большом чате (тысячи
     * задач, десятки проектов) не тянем все строки в память.
     * <p>
     * Порядок колонок: {@code repo, cnt, label}.
     */
    @Query(value = """
            SELECT repo_norm, cnt, label
            FROM (
                SELECT LOWER(TRIM(repo)) AS repo_norm,
                       COUNT(*) OVER (PARTITION BY LOWER(TRIM(repo))) AS cnt,
                       COALESCE(NULLIF(TRIM(title), ''), description) AS label,
                       ROW_NUMBER() OVER (PARTITION BY LOWER(TRIM(repo))
                                          ORDER BY COALESCE(updated_at, created_at) DESC) AS rn,
                       MAX(COALESCE(updated_at, created_at)) OVER (PARTITION BY LOWER(TRIM(repo))) AS last_used
                FROM agent_tasks
                WHERE notify_chat_id = :chatId
                  AND deleted = false
                  AND repo IS NOT NULL
                  AND TRIM(repo) <> ''
            ) sub
            WHERE rn = 1
            ORDER BY last_used DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findChatProjects(@Param("chatId") long chatId, @Param("limit") int limit);

    /**
     * Уникальные целевые репозитории не удалённых задач (для мониторинга PR-комментариев) —
     * свежие первыми, не более {@code limit}. PR задачи живёт в ЕЁ репозитории, поэтому
     * монитор должен опрашивать именно их, а не один сконфигурированный репозиторий.
     */
    @Query(value = """
            SELECT repo_norm
            FROM (
                SELECT LOWER(TRIM(repo)) AS repo_norm,
                       MAX(COALESCE(updated_at, created_at)) AS last_used
                FROM agent_tasks
                WHERE deleted = false
                  AND repo IS NOT NULL
                  AND TRIM(repo) <> ''
                GROUP BY LOWER(TRIM(repo))
            ) sub
            ORDER BY last_used DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<String> findDistinctRepos(@Param("limit") int limit);
}
