package ru.allstreets.developer.opencode;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Прогресс OpenCode агента. Мутации в reader-потоке (tool/text/step события) —
 * атомарные UPDATE на стороне БД без read-modify-write, чтобы избежать race
 * condition с основным потоком агента. Создание и сброс записи — через
 * стандартный JPA {@code save()} ({@link TaskProgressEntity} реализует
 * {@link Persistable} для корректного persist/merge при assigned ID).
 */
@Repository
public interface TaskProgressRepository extends JpaRepository<TaskProgressEntity, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE task_progress SET
                current_tool = :tool,
                tool_calls = CASE WHEN COALESCE(tool_calls, '') = '' THEN :tool ELSE tool_calls || ',' || :tool END,
                recent_events = CASE WHEN COALESCE(recent_events, '') = '' THEN :event ELSE recent_events || E'\\n' || :event END,
                last_update_ms = :now
            WHERE task_id = :taskId
            """, nativeQuery = true)
    void appendToolCall(@Param("taskId") String taskId,
                        @Param("tool") String tool,
                        @Param("event") String event,
                        @Param("now") long now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE task_progress SET
                total_tokens = total_tokens + :tokens,
                cost = cost + :cost,
                step_count = step_count + 1,
                recent_events = CASE WHEN COALESCE(recent_events, '') = '' THEN :event ELSE recent_events || E'\\n' || :event END,
                last_update_ms = :now
            WHERE task_id = :taskId
            """, nativeQuery = true)
    void appendStepFinish(@Param("taskId") String taskId,
                          @Param("tokens") long tokens,
                          @Param("cost") double cost,
                          @Param("event") String event,
                          @Param("now") long now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE task_progress SET
                last_text = :text,
                recent_events = CASE WHEN COALESCE(recent_events, '') = '' THEN :event ELSE recent_events || E'\\n' || :event END,
                last_update_ms = :now
            WHERE task_id = :taskId
            """, nativeQuery = true)
    void appendText(@Param("taskId") String taskId,
                    @Param("text") String text,
                    @Param("event") String event,
                    @Param("now") long now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE task_progress SET
                error = :error,
                recent_events = CASE WHEN COALESCE(recent_events, '') = '' THEN :event ELSE recent_events || E'\\n' || :event END,
                last_update_ms = :now
            WHERE task_id = :taskId
            """, nativeQuery = true)
    void appendError(@Param("taskId") String taskId,
                     @Param("error") String error,
                     @Param("event") String event,
                     @Param("now") long now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE task_progress SET finished = true, last_update_ms = :now WHERE task_id = :taskId",
            nativeQuery = true)
    void markFinished(@Param("taskId") String taskId, @Param("now") long now);

}
