package ru.allstreets.developer.opencode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Реестр прогресса OpenCode агентов по taskId, persists в БД.
 */
@Component
public class TaskProgressRegistry {

    private static final Logger log = LoggerFactory.getLogger(TaskProgressRegistry.class);
    private static final int MAX_EVENTS = 20;

    private final TaskProgressRepository repo;
    private final TaskRepository taskRepo;

    public TaskProgressRegistry(TaskProgressRepository repo, TaskRepository taskRepo) {
        this.repo = repo;
        this.taskRepo = taskRepo;
    }

    @Transactional
    public void start(String taskId, String agentName) {
        TaskEntity task = taskRepo.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("TaskProgressRegistry: task not found taskId={}, прогресс не сохранён", taskId);
            return;
        }
        TaskProgressEntity existing = repo.findByIdForUpdate(taskId).orElse(null);
        if (existing != null) {
            existing.setAgentName(agentName);
            existing.setCurrentTool(null);
            existing.setToolCalls("");
            existing.setRecentEvents("");
            existing.setTotalTokens(0);
            existing.setCost(0);
            existing.setStepCount(0);
            existing.setLastText(null);
            existing.setError(null);
            existing.setStartTimeMs(System.currentTimeMillis());
            existing.setLastUpdateMs(System.currentTimeMillis());
            existing.setFinished(false);
            repo.save(existing);
        } else {
            repo.save(new TaskProgressEntity(task, agentName));
        }
        log.debug("TaskProgressRegistry: start taskId={}, agent={}", taskId, agentName);
    }

    @Transactional
    public void recordToolCall(String taskId, String toolName) {
        repo.findByIdForUpdate(taskId).ifPresent(e -> {
            e.setCurrentTool(toolName);
            e.setToolCalls(appendCsv(e.getToolCalls(), toolName));
            e.setLastUpdateMs(System.currentTimeMillis());
            e.setRecentEvents(appendEvent(e.getRecentEvents(), "tool: " + toolName));
            repo.save(e);
        });
    }

    @Transactional
    public void recordStepFinish(String taskId, long tokens, double cost, String reason) {
        repo.findByIdForUpdate(taskId).ifPresent(e -> {
            e.setTotalTokens(e.getTotalTokens() + tokens);
            e.setCost(e.getCost() + cost);
            e.setStepCount(e.getStepCount() + 1);
            e.setLastUpdateMs(System.currentTimeMillis());
            e.setRecentEvents(appendEvent(e.getRecentEvents(),
                    "step #" + e.getStepCount() + " done (reason=" + reason + ", tokens=" + e.getTotalTokens() + ")"));
            repo.save(e);
        });
    }

    @Transactional
    public void recordText(String taskId, String text) {
        repo.findByIdForUpdate(taskId).ifPresent(e -> {
            e.setLastText(text);
            e.setLastUpdateMs(System.currentTimeMillis());
            e.setRecentEvents(appendEvent(e.getRecentEvents(),
                    "text: " + (text.length() > 100 ? text.substring(0, 100) + "..." : text)));
            repo.save(e);
        });
    }

    @Transactional
    public void recordError(String taskId, String error) {
        repo.findByIdForUpdate(taskId).ifPresent(e -> {
            e.setError(error);
            e.setLastUpdateMs(System.currentTimeMillis());
            e.setRecentEvents(appendEvent(e.getRecentEvents(), "error: " + error));
            repo.save(e);
        });
    }

    @Transactional
    public void markFinished(String taskId) {
        repo.findByIdForUpdate(taskId).ifPresent(e -> {
            e.setFinished(true);
            e.setLastUpdateMs(System.currentTimeMillis());
            repo.save(e);
        });
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
        parseLines(e.getRecentEvents()).forEach(p.getRecentEvents()::add);
        return p;
    }

    private String appendCsv(String existing, String value) {
        return existing == null || existing.isEmpty() ? value : existing + "," + value;
    }

    private String appendEvent(String existing, String event) {
        Deque<String> events = new ArrayDeque<>(parseLines(existing));
        events.addLast(event);
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
        return String.join("\n", events);
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
