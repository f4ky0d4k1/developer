package ru.allstreets.developer.opencode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.allstreets.developer.checkpoint.TaskRepository;

import java.util.List;

/**
 * Реестр прогресса OpenCode агентов по taskId, persists в БД.
 * <p>
 * Все мутации выполняются атомарными UPDATE на стороне БД
 * ({@link TaskProgressRepository}), поэтому конкурентные вызовы из
 * reader-потока OpenCode и основного потока агента безопасны без
 * блокировок и retry: БД сериализует UPDATE на уровне строки.
 */
@Component
public class TaskProgressRegistry {

    private static final Logger log = LoggerFactory.getLogger(TaskProgressRegistry.class);
    private static final int MAX_EVENTS = 20;
    private static final int MAX_TEXT_PREVIEW = 100;

    private final TaskProgressRepository repo;
    private final TaskRepository taskRepo;

    public TaskProgressRegistry(TaskProgressRepository repo, TaskRepository taskRepo) {
        this.repo = repo;
        this.taskRepo = taskRepo;
    }

    /**
     * Старт агента: создаёт запись прогресса, если её ещё нет, либо сбрасывает
     * существующую. Стандартный JPA-паттерн: findById → mutate → save.
     */
    @Transactional
    public void start(String taskId, String agentName) {
        if (taskRepo.findById(taskId).isEmpty()) {
            log.warn("TaskProgressRegistry: task not found taskId={}, прогресс не сохранён", taskId);
            return;
        }
        long now = System.currentTimeMillis();
        TaskProgressEntity entity = repo.findById(taskId).orElseGet(() -> new TaskProgressEntity(taskId, agentName));
        entity.setAgentName(agentName);
        entity.setCurrentTool(null);
        entity.setToolCalls("");
        entity.setRecentEvents("");
        entity.setTotalTokens(0);
        entity.setCost(0);
        entity.setStepCount(0);
        entity.setLastText(null);
        entity.setError(null);
        entity.setStartTimeMs(now);
        entity.setLastUpdateMs(now);
        entity.setFinished(false);
        repo.save(entity);
        log.debug("TaskProgressRegistry: start taskId={}, agent={}", taskId, agentName);
    }

    @Transactional
    public void recordToolCall(String taskId, String toolName) {
        repo.appendToolCall(taskId, toolName, "tool: " + toolName, System.currentTimeMillis());
    }

    @Transactional
    public void recordStepFinish(String taskId, long tokens, double cost, String reason) {
        repo.appendStepFinish(taskId, tokens, cost,
                "step done (reason=" + reason + ", tokens=" + tokens + ")", System.currentTimeMillis());
    }

    @Transactional
    public void recordText(String taskId, String text) {
        repo.appendText(taskId, text, "text: " + preview(text), System.currentTimeMillis());
    }

    @Transactional
    public void recordError(String taskId, String error) {
        repo.appendError(taskId, error, "error: " + error, System.currentTimeMillis());
    }

    @Transactional
    public void markFinished(String taskId) {
        repo.markFinished(taskId, System.currentTimeMillis());
    }

    private String preview(String text) {
        if (text == null) return "";
        return text.length() > MAX_TEXT_PREVIEW ? text.substring(0, MAX_TEXT_PREVIEW) + "..." : text;
    }

    public TaskProgress get(String taskId) {
        return repo.findById(taskId).map(this::toProgress).orElse(null);
    }

    private TaskProgress toProgress(TaskProgressEntity e) {
        TaskProgress p = new TaskProgress(e.getAgentName());
        p.setCurrentTool(e.getCurrentTool());
        p.setTotalTokens(e.getTotalTokens());
        p.setCost(e.getCost());
        p.setStepCount(e.getStepCount());
        p.setLastText(e.getLastText());
        p.setError(e.getError());
        p.setFinished(e.isFinished());
        parseCsv(e.getToolCalls()).forEach(p.getToolCalls()::add);
        lastN(parseLines(e.getRecentEvents()), MAX_EVENTS).forEach(p.getRecentEvents()::add);
        return p;
    }

    /**
     * Обрезка истории событий на чтении: UPDATE в БД только дописывает,
     * ограничение применяется здесь.
     */
    @SuppressWarnings("SameParameterValue")
    private List<String> lastN(List<String> items, int n) {
        return items.size() <= n ? items : items.subList(items.size() - n, items.size());
    }

    private List<String> parseCsv(String csv) {
        if (csv == null || csv.isEmpty()) return List.of();
        return List.of(csv.split(","));
    }

    private List<String> parseLines(String text) {
        if (text == null || text.isEmpty()) return List.of();
        return List.of(text.split("\n"));
    }
}
