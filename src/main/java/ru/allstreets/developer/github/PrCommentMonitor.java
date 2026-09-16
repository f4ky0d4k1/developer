package ru.allstreets.developer.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.telegram.ActiveTaskRegistry;
import ru.allstreets.developer.telegram.ChatMemoryService;
import ru.allstreets.developer.telegram.TaskLauncher;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Мониторинг комментариев в agent-generated PR.
 * <p>
 * Новые комментарии НЕ создают отдельную задачу: они возвращают в работу ИСХОДНУЮ задачу PR
 * (её находят по ветке PR — {@link TaskRepository#findByGitBranch}). {@link TaskLauncher#rework}
 * прерывает текущий ран (если он идёт) и перезапускает граф с аналитика, добавив комментарии
 * к описанию задачи — аналитик из вводных сам решает, что дописать (тесты/код). Один PR = одна
 * задача, без плодения новых и без дублей Tracker.
 * <p>
 * Опрашиваются репозитории задач ({@link TaskRepository#findDistinctRepos}) — PR задачи живёт
 * в её репозитории, {@code github.monitor-repo} лишь fallback (инцидент 15.09: монитор смотрел
 * f4ky0d4k1/developer, а PR был в iamponamarev/allstreets-spring).
 * <p>
 * Дедуп — persistent ({@link ProcessedPrCommentEntity}): комментарий помечается обработанным
 * только если реитерация реально поставлена; между рестартами не повторяется.
 */
@Component
public class PrCommentMonitor {

    private static final Logger log = LoggerFactory.getLogger(PrCommentMonitor.class);

    /**
     * Сколько репозиториев задач максимум опрашивать за один тик (свежие первыми).
     */
    private static final int MONITOR_REPO_LIMIT = 20;

    /**
     * Метка служебных комментариев агента (его итоговые отчёты в PR). Такие комментарии
     * НЕ считаются вводными — иначе каждый прогон постит отчёт, монитор видит «новый комментарий»
     * и запускает следующую реитерацию (самоподдерживающаяся петля).
     */
    static final String AGENT_REPORT_MARKER = "<!-- agent-report -->";

    private final GitHubService github;
    private final TaskLauncher taskLauncher;
    private final TelegramGateway telegram;
    private final ChatMemoryService chatMemory;
    private final ActiveTaskRegistry taskRegistry;
    private final TaskRepository taskRepo;
    private final ProcessedPrCommentRepository processedComments;

    // fallback chatId для уведомлений (берётся из whitelist — первый)
    private final long fallbackNotifyChatId;

    // Репозиторий для мониторинга PR (fallback, если у задач репозиторий не заполнен)
    private final String monitorRepo;

    public PrCommentMonitor(
            GitHubService github,
            TaskLauncher taskLauncher,
            TelegramGateway telegram,
            ChatMemoryService chatMemory,
            ActiveTaskRegistry taskRegistry,
            TaskRepository taskRepo,
            ProcessedPrCommentRepository processedComments,
            @Value("${telegram.allowed-chat-ids:}") String allowedChatIds,
            @Value("${github.monitor-repo:}") String monitorRepo
    ) {
        this.github = github;
        this.taskLauncher = taskLauncher;
        this.telegram = telegram;
        this.chatMemory = chatMemory;
        this.taskRegistry = taskRegistry;
        this.taskRepo = taskRepo;
        this.processedComments = processedComments;
        this.monitorRepo = monitorRepo;

        long chatId = 0;
        if (allowedChatIds != null && !allowedChatIds.isBlank()) {
            String first = allowedChatIds.split(",")[0].trim();
            if (!first.isEmpty()) {
                try {
                    chatId = Long.parseLong(first);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        this.fallbackNotifyChatId = chatId;
    }

    private long resolveNotifyChatId(String taskId, String branchName) {
        // 1. По taskId из ActiveTaskRegistry
        if (taskId != null) {
            Long notifyId = taskRegistry.getNotifyChatId(taskId);
            if (notifyId != null && notifyId > 0) return notifyId;
        }
        // 2. По branch name из PR → TaskEntity.gitBranch
        if (branchName != null && !branchName.isBlank()) {
            var task = taskRepo.findByGitBranch(branchName);
            if (task.isPresent() && task.get().getNotifyChatId() != null) {
                return task.get().getNotifyChatId();
            }
        }
        // 3. Fallback — первый chatId из whitelist
        return fallbackNotifyChatId;
    }

    /**
     * Опрос PR каждые 60 секунд.
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 15000)
    public void monitorPullRequests() {
        List<String> repos = reposToMonitor();
        if (repos.isEmpty()) {
            log.debug("Мониторинг PR: нет репозиториев для опроса (ни задач с repo, ни github.monitor-repo)");
            return;
        }

        for (String repo : repos) {
            try {
                List<GitHubService.PrInfo> prs = github.listAgentPullRequests(repo);
                if (prs.isEmpty()) {
                    continue;
                }

                log.debug("Мониторинг: найдено {} открытых agent PR в {}", prs.size(), repo);

                for (GitHubService.PrInfo pr : prs) {
                    processPrComments(repo, pr);
                }
            } catch (Exception e) {
                log.error("Ошибка мониторинга PR в {}: {}", repo, e.getMessage(), e);
            }
        }
    }

    /**
     * Репозитории для мониторинга: целевые репозитории задач (свежие первыми, дедуп) плюс
     * {@code github.monitor-repo} как fallback. PR задачи живёт в её репозитории, поэтому
     * одного сконфигурированного репо недостаточно.
     */
    List<String> reposToMonitor() {
        var repos = new LinkedHashSet<String>();
        try {
            repos.addAll(taskRepo.findDistinctRepos(MONITOR_REPO_LIMIT));
        } catch (Exception e) {
            log.warn("Мониторинг: не удалось получить репозитории задач: {}", e.getMessage());
        }
        if (monitorRepo != null && !monitorRepo.isBlank()) {
            repos.add(monitorRepo.trim());
        }
        return List.copyOf(repos);
    }

    private void processPrComments(String repo, GitHubService.PrInfo pr) {
        try {
            List<GitHubService.PrComment> fresh = github.listPrComments(repo, pr.number()).stream()
                    .filter(c -> !processedComments.existsById(c.id()))
                    .filter(c -> c.body() != null && !c.body().isBlank())
                    // Служебные отчёты агента — не вводные, игнорируем (см. AGENT_REPORT_MARKER).
                    .filter(c -> !c.body().contains(AGENT_REPORT_MARKER))
                    .toList();
            if (fresh.isEmpty()) {
                return;
            }

            // Исходная задача PR — по ветке. Доработка идёт в НЕЁ, а не в новую задачу.
            TaskEntity task = taskRepo.findByGitBranch(pr.headBranch()).orElse(null);
            if (task == null) {
                log.warn("Мониторинг: для ветки {} (PR #{}) не найдена задача — новые комментарии оставлены "
                        + "необработанными до появления задачи", pr.headBranch(), pr.number());
                return;
            }

            String commentsText = fresh.stream()
                    .map(c -> "- %s: %s".formatted(c.author(), c.body()))
                    .collect(Collectors.joining("\n"));

            log.info("Новые комментарии в PR #{} ({}) — возвращаю задачу {} в работу ({} шт.)",
                    pr.number(), repo, task.getTaskId().substring(0, 8), fresh.size());

            long chatId = resolveNotifyChatId(task.getTaskId(), pr.headBranch());
            if (chatId > 0) {
                String msg = "💬 Новые комментарии в PR #%d (%s) — возвращаю задачу %s в работу:\n%s".formatted(
                        pr.number(), pr.htmlUrl(), task.getTaskId().substring(0, 8), commentsText);
                telegram.sendMessage(chatId, msg, task.getTaskId());
                chatMemory.recordBotMessage(chatId, msg, task.getTaskId());
            }

            boolean started = taskLauncher.rework(task.getTaskId(), chatId, commentsText);
            if (started) {
                for (GitHubService.PrComment c : fresh) {
                    markProcessed(c.id(), pr.number(), repo);
                }
            }
        } catch (Exception e) {
            log.error("Ошибка обработки комментариев PR #{} в {}: {}", pr.number(), repo, e.getMessage(), e);
        }
    }

    /**
     * Пометить комментарий обработанным. Сбой записи не должен ронять тик мониторинга:
     * в худшем случае комментарий обработается ещё раз (как было до persistent-дедупа).
     */
    private void markProcessed(long commentId, int prNumber, String repo) {
        try {
            processedComments.save(new ProcessedPrCommentEntity(commentId, prNumber, repo));
        } catch (Exception e) {
            log.warn("Мониторинг: не удалось сохранить обработанный комментарий {} ({}): {}",
                    commentId, repo, e.getMessage());
        }
    }
}
