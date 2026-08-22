package ru.allstreets.developer.humanloop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.checkpoint.PendingInputEntity;
import ru.allstreets.developer.checkpoint.PendingInputRepository;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Реестр ожидаемых ответов от человека — per-task, DB-backed.
 * Агент создаёт CompletableFuture и блокируется на нём.
 * Pending questions persist в БД — при рестарте можно ре-нотифицировать пользователя.
 * ConversationAgent решает, какое сообщение является ответом для какой задачи.
 */
@Component
public class HumanInputRegistry {

    private static final Logger log = LoggerFactory.getLogger(HumanInputRegistry.class);

    private final Map<String, PendingInput> pendingInputs = new ConcurrentHashMap<>();
    private final PendingInputRepository pendingRepo;

    public HumanInputRegistry(PendingInputRepository pendingRepo) {
        this.pendingRepo = pendingRepo;
    }

    public String requestInput(String taskId, long chatId, String question, long timeoutSeconds) {
        var future = new CompletableFuture<String>();
        pendingInputs.put(taskId, new PendingInput(question, chatId, future, System.currentTimeMillis()));
        pendingRepo.save(new PendingInputEntity(taskId, chatId, question));

        log.info("HumanInput: запрос для taskId={} chatId={}, ожидание до {}с", taskId, chatId, timeoutSeconds);

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("HumanInput: таймаут для taskId={}", taskId);
            pendingInputs.remove(taskId);
            pendingRepo.deleteById(taskId);
            return null;
        } catch (Exception e) {
            log.error("HumanInput: ошибка ожидания для taskId={}: {}", taskId, e.getMessage());
            pendingInputs.remove(taskId);
            pendingRepo.deleteById(taskId);
            return null;
        }
    }

    public boolean hasPendingInputs(long chatId) {
        return pendingInputs.values().stream().anyMatch(p -> p.chatId() == chatId);
    }

    public Map<String, String> getPendingQuestionsForChat(long chatId) {
        var result = new java.util.LinkedHashMap<String, String>();
        for (var entry : pendingInputs.entrySet()) {
            if (entry.getValue().chatId() == chatId) {
                result.put(entry.getKey(), entry.getValue().question());
            }
        }
        return result;
    }

    public void provideAnswer(String taskId, String answer) {
        var pending = pendingInputs.remove(taskId);
        if (pending != null) {
            log.info("HumanInput: ответ получен для taskId={}", taskId);
            pending.future().complete(answer);
            pendingRepo.deleteById(taskId);
        } else {
            log.warn("HumanInput: нет pending запроса для taskId={}", taskId);
        }
    }

    public void cancel(String taskId) {
        var pending = pendingInputs.remove(taskId);
        if (pending != null) {
            pending.future().cancel(true);
            pendingRepo.deleteById(taskId);
        }
    }

    public record PendingInput(String question, long chatId, CompletableFuture<String> future, long createdAt) {
    }
}
