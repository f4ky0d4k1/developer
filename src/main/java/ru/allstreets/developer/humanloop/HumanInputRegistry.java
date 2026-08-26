package ru.allstreets.developer.humanloop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.checkpoint.PendingInputEntity;
import ru.allstreets.developer.checkpoint.PendingInputRepository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реестр pending HITL-вопросов — per-task, DB-backed.
 * <p>
 * Не блокирует потоки: вопрос приостанавливает граф через
 * {@code AgentResult.interrupted(...)} и сохраняет checkpoint — поток из пула
 * возвращается сразу. Ответ пользователя запускает {@code TaskLauncher.resumeWithAnswer(...)},
 * который вызывает {@code graph.resume(runId, answerMessage)} — продолжение той же
 * OpenCode-сессии в том же зарезервированном слоте (см. AnalystNode), без перезапуска.
 * <p>
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

    /**
     * Зарегистрировать вопрос, ожидающий ответа. Не блокирует — граф уже
     * приостановлен через interrupt, вызывающий агент вернул управление немедленно.
     */
    public void registerPending(String taskId, long chatId, String question) {
        pendingInputs.put(taskId, new PendingInput(question, chatId, System.currentTimeMillis()));
        pendingRepo.save(new PendingInputEntity(taskId, chatId, question));
        log.info("HumanInput: зарегистрирован pending-вопрос для taskId={} chatId={}", taskId, chatId);
    }

    /**
     * chatId, для которого зарегистрирован pending-вопрос данной задачи, или null.
     */
    public Long getChatIdForPending(String taskId) {
        var pending = pendingInputs.get(taskId);
        return pending != null ? pending.chatId() : null;
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

    /**
     * Снять pending-вопрос после того, как ответ получен и запущен resume задачи.
     * Ничего не блокирует и не завершает — это делает {@code TaskLauncher.resumeWithAnswer}.
     */
    public void provideAnswer(String taskId) {
        var pending = pendingInputs.remove(taskId);
        if (pending != null) {
            log.info("HumanInput: ответ получен для taskId={}", taskId);
            pendingRepo.deleteById(taskId);
        } else {
            log.warn("HumanInput: нет pending запроса для taskId={}", taskId);
        }
    }

    public void cancel(String taskId) {
        if (pendingInputs.remove(taskId) != null) {
            pendingRepo.deleteById(taskId);
        }
    }

    public record PendingInput(String question, long chatId, long createdAt) {
    }
}
