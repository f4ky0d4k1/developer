package ru.allstreets.developer.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Единая точка жизненного цикла forum-темы задачи (BACKEND-443).
 * <p>
 * Резолвер {@code taskId → threadId} с in-memory кэшем: истина — БД, кэш
 * перестраивается из БД после рестарта или перепривязки ({@link #invalidate}).
 * <p>
 * Создание темы идемпотентно: параллельные вызовы для одной задачи создают ровно
 * одну тему за счёт атомарного claim
 * ({@link TaskRepository#claimThreadId(String, Long)}); проигравший гонку перечитывает
 * победивший {@code thread_id} и НЕ перезаписывает его.
 * <p>
 * Наследник ({@code parentTaskId}) свою тему не создаёт — наследует тему КОРНЯ цепочки.
 * <p>
 * Все обращения к Telegram fail-open: удалённая тема / нет прав / 429 не валят задачу,
 * создание возвращает {@code null} (вызывающий уходит в fallback — общий чат + reply-to),
 * а lifecycle-методы возвращают {@code false} и логируют причину.
 */
@Component
public class TelegramTopicService {

    private static final Logger log = LoggerFactory.getLogger(TelegramTopicService.class);

    /**
     * Максимальная длина имени forum-темы по правилам Telegram Bot API.
     */
    static final int MAX_TOPIC_NAME = 128;

    private final TelegramGateway gateway;
    private final TaskRepository taskRepo;
    private final boolean enabled;
    private final Map<String, Long> threadCache = new ConcurrentHashMap<>();

    /**
     * Юнит-тестовый/совместимый конструктор: forum-темы включены.
     */
    public TelegramTopicService(TelegramGateway gateway, TaskRepository taskRepo) {
        this(gateway, taskRepo, true);
    }

    @Autowired
    public TelegramTopicService(TelegramGateway gateway, TaskRepository taskRepo,
                                @Value("${telegram.forum-topics.enabled:true}") boolean enabled) {
        this.gateway = gateway;
        this.taskRepo = taskRepo;
        this.enabled = enabled;
    }

    // ------------------------------------------------------------------
    // Резолвер taskId → threadId
    // ------------------------------------------------------------------

    /**
     * thread_id задачи: сначала кэш, затем БД (истина). {@code null} — темы нет.
     * {@code null} не кэшируется, чтобы подхватить тему, созданную параллельно.
     */
    public Long resolveThreadId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        Long cached = threadCache.get(taskId);
        if (cached != null) {
            return cached;
        }
        Long threadId = taskRepo.findById(taskId).map(TaskEntity::getThreadId).orElse(null);
        if (threadId != null) {
            threadCache.put(taskId, threadId);
        }
        return threadId;
    }

    /**
     * Сбросить кэш задачи (создание/переименование/закрытие/перепривязка, рестарт).
     * Следующее чтение перестроит значение из БД.
     */
    public void invalidate(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            threadCache.remove(taskId);
        }
    }

    // ------------------------------------------------------------------
    // Санитизация имени
    // ------------------------------------------------------------------

    /**
     * Имя forum-темы: одна строка, ≤128 символов, непустое. Переносы/табы схлопываются
     * в пробел; при пустом/отсутствующем названии fallback — на ID задачи, иначе тему
     * невозможно сопоставить с задачей.
     */
    public static String sanitizeTopicName(String title, String taskId) {
        String base = (title != null && !title.isBlank())
                ? title
                : (taskId != null && !taskId.isBlank() ? taskId : "task");
        String oneLine = base.replaceAll("\\s+", " ").trim();
        if (oneLine.isBlank()) {
            oneLine = "task";
        }
        if (oneLine.length() > MAX_TOPIC_NAME) {
            oneLine = oneLine.substring(0, MAX_TOPIC_NAME).trim();
        }
        return oneLine;
    }

    // ------------------------------------------------------------------
    // Создание / наследование темы
    // ------------------------------------------------------------------

    /**
     * Обеспечить forum-тему задачи и вернуть её {@code thread_id}.
     * <ul>
     *   <li>тема уже есть — вернуть без обращения к Telegram;</li>
     *   <li>наследник ({@code parentTaskId} задан) — унаследовать тему корня, свою не создавать;</li>
     *   <li>корень — создать тему, атомарно заclaim'ить; проигравший гонку вернёт тему победителя.</li>
     * </ul>
     * Любая ошибка Telegram/БД — логируется и возвращает {@code null} (fallback).
     */
    public Long ensureTopic(String taskId, long chatId, String title) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        if (!enabled) {
            log.debug("TelegramTopicService: forum-темы отключены конфигом — задача {} в общем чате",
                    shortId(taskId));
            return null;
        }
        try {
            TaskEntity task = taskRepo.findById(taskId).orElse(null);
            if (task == null) {
                log.warn("TelegramTopicService: задача {} не найдена — тема не создаётся", shortId(taskId));
                return null;
            }
            if (task.getThreadId() != null) {
                threadCache.put(taskId, task.getThreadId());
                return task.getThreadId();
            }
            if (task.getParentTaskId() != null && !task.getParentTaskId().isBlank()) {
                Long rootThread = resolveRootThreadId(task.getParentTaskId());
                if (rootThread == null) {
                    log.warn("TelegramTopicService: у корня цепочки задачи {} нет темы — наследник без топика",
                            shortId(taskId));
                    return null;
                }
                task.setThreadId(rootThread);
                task.setRootThreadId(rootThread);
                saveQuietly(task);
                threadCache.put(taskId, rootThread);
                log.info("TelegramTopicService: наследник {} унаследовал тему корня {} (thread={})",
                        shortId(taskId), shortId(task.getParentTaskId()), rootThread);
                return rootThread;
            }

            String name = sanitizeTopicName(title, taskId);
            Long created = gateway.createForumTopic(chatId, name);
            if (created == null) {
                log.warn("TelegramTopicService: тему для задачи {} (chat={}) создать не удалось — fallback на общий чат",
                        shortId(taskId), chatId);
                return null;
            }
            int claimed = taskRepo.claimThreadId(taskId, created);
            if (claimed == 0) {
                Long winner = taskRepo.findById(taskId).map(TaskEntity::getThreadId).orElse(null);
                log.info("TelegramTopicService: гонка создания темы для {} — принята тема победителя {}",
                        shortId(taskId), winner);
                if (winner != null) {
                    threadCache.put(taskId, winner);
                }
                return winner;
            }
            threadCache.put(taskId, created);
            log.info("TelegramTopicService: создана тема для задачи {} (chat={}, thread={})",
                    shortId(taskId), chatId, created);
            return created;
        } catch (Exception e) {
            log.warn("TelegramTopicService: ensureTopic task={} chat={} ошибка: {}", shortId(taskId), chatId, e.getMessage());
            return null;
        }
    }

    /**
     * Подъём по цепочке {@code parent_task_id} до задачи с готовой темой (корень).
     */
    private Long resolveRootThreadId(String parentTaskId) {
        String current = parentTaskId;
        Set<String> seen = new HashSet<>();
        while (current != null && !current.isBlank() && seen.add(current)) {
            Long cached = threadCache.get(current);
            if (cached != null) {
                return cached;
            }
            TaskEntity task = taskRepo.findById(current).orElse(null);
            if (task == null) {
                return null;
            }
            if (task.getThreadId() != null) {
                threadCache.put(current, task.getThreadId());
                return task.getThreadId();
            }
            current = task.getParentTaskId();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Lifecycle: rename / close / pin (идемпотентно, fail-open)
    // ------------------------------------------------------------------

    /**
     * Переименовать тему задачи. {@code false} — темы нет или Telegram отказал.
     */
    public boolean renameTopic(String taskId, String newName) {
        TaskEntity task = findTask(taskId);
        if (task == null || task.getThreadId() == null || task.getNotifyChatId() == null) {
            return false;
        }
        try {
            boolean ok = gateway.editForumTopic(task.getNotifyChatId(), task.getThreadId(),
                    sanitizeTopicName(newName, taskId));
            if (ok) {
                log.info("TelegramTopicService: тема {} переименована (task={})",
                        task.getThreadId(), shortId(taskId));
            }
            return ok;
        } catch (Exception e) {
            log.warn("TelegramTopicService: renameTopic task={} ошибка: {}", shortId(taskId), e.getMessage());
            return false;
        }
    }

    /**
     * Закрыть тему задачи (closeForumTopic). Повторный вызов не ошибка.
     */
    public boolean closeTopic(String taskId) {
        TaskEntity task = findTask(taskId);
        if (task == null || task.getThreadId() == null || task.getNotifyChatId() == null) {
            return false;
        }
        try {
            boolean ok = gateway.closeForumTopic(task.getNotifyChatId(), task.getThreadId());
            if (ok) {
                log.info("TelegramTopicService: тема {} закрыта (task={})", task.getThreadId(), shortId(taskId));
            }
            return ok;
        } catch (Exception e) {
            log.warn("TelegramTopicService: closeTopic task={} ошибка: {}", shortId(taskId), e.getMessage());
            return false;
        }
    }

    /**
     * Закрепить сообщение-описание в теме задачи. Ошибки прав/лимитов не валят задачу.
     */
    public boolean pinTopicDescription(String taskId, String description) {
        TaskEntity task = findTask(taskId);
        if (task == null || task.getThreadId() == null || task.getNotifyChatId() == null) {
            return false;
        }
        try {
            return gateway.pinChatMessage(task.getNotifyChatId(), task.getThreadId());
        } catch (Exception e) {
            log.warn("TelegramTopicService: pinTopicDescription task={} ошибка: {}", shortId(taskId), e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Перепривязка / листинг (для MCP-тулов)
    // ------------------------------------------------------------------

    /**
     * Перепривязать задачу и ВСЕХ её наследников к новой теме, согласованно обновив
     * {@code thread_id}/{@code root_thread_id}. Возвращает затронутые taskId.
     */
    public List<String> reassignTopic(String taskId, Long newThreadId) {
        TaskEntity task = findTask(taskId);
        if (task == null || newThreadId == null) {
            return List.of();
        }
        java.util.List<String> affected = new java.util.ArrayList<>();
        task.setThreadId(newThreadId);
        task.setRootThreadId(newThreadId);
        saveQuietly(task);
        invalidate(taskId);
        affected.add(taskId);

        var queue = new java.util.ArrayDeque<String>();
        queue.add(taskId);
        while (!queue.isEmpty()) {
            String parent = queue.poll();
            for (TaskEntity child : taskRepo.findByParentTaskId(parent)) {
                child.setThreadId(newThreadId);
                child.setRootThreadId(newThreadId);
                saveQuietly(child);
                invalidate(child.getTaskId());
                affected.add(child.getTaskId());
                queue.add(child.getTaskId());
            }
        }
        log.info("TelegramTopicService: задача {} и {} наследников перепривязаны к теме {}",
                shortId(taskId), affected.size() - 1, newThreadId);
        return affected;
    }

    /**
     * Задачи чата, для которых известна forum-тема (источник истины {@code getForumTopics}).
     */
    public List<TaskEntity> topicsForChat(long chatId) {
        return taskRepo.findByNotifyChatIdAndThreadIdIsNotNullOrderByCreatedAtDesc(chatId);
    }

    private TaskEntity findTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        return taskRepo.findById(taskId).orElse(null);
    }

    /**
     * Сохранение наследника/перепривязки: кэш не должен «упасть» из-за сбоя БД.
     */
    private void saveQuietly(TaskEntity task) {
        try {
            taskRepo.save(task);
        } catch (Exception e) {
            log.warn("TelegramTopicService: не удалось сохранить тему задачи {}: {}",
                    shortId(task.getTaskId()), e.getMessage());
        }
    }

    private static String shortId(String taskId) {
        return taskId != null && taskId.length() > 8 ? taskId.substring(0, 8) : taskId;
    }
}
