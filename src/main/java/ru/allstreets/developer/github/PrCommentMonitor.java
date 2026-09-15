package ru.allstreets.developer.github;

import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.config.AgentGraphRunner;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.ActiveTaskRegistry;
import ru.allstreets.developer.telegram.ChatMemoryService;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Мониторинг комментариев в agent-generated PR.
 * Периодически опрашивает GitHub на предмет новых комментариев.
 * При обнаружении нового комментария запускает агентный граф с инструкцией из комментария.
 * <p>
 * PR задачи живёт в ЕЁ целевом репозитории (owner/name), поэтому опрашиваются репозитории
 * задач из реестра ({@link TaskRepository#findDistinctRepos}), а {@code github.monitor-repo}
 * остаётся лишь fallback'ом — иначе PR в целевом репо не виден (инцидент 15.09: монитор
 * смотрел на f4ky0d4k1/developer, а PR был в iamponamarev/allstreets-spring).
 * <p>
 * Дедуп — persistent ({@link ProcessedPrCommentEntity}): обработанный комментарий не
 * запускается повторно после рестарта приложения или на другом инстансе.
 */
@Component
public class PrCommentMonitor {

    private static final Logger log = LoggerFactory.getLogger(PrCommentMonitor.class);

    /** Сколько репозиториев задач максимум опрашивать за один тик (свежие первыми). */
    private static final int MONITOR_REPO_LIMIT = 20;

    private final GitHubService github;
    private final AgentGraphRunner graphRunner;
    private final TelegramGateway telegram;
    private final ChatMemoryService chatMemory;
    private final ActiveTaskRegistry taskRegistry;
    private final TaskRepository taskRepo;
    private final ProcessedPrCommentRepository processedComments;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // fallback chatId для уведомлений (берётся из whitelist — первый)
    private final long fallbackNotifyChatId;

    // Репозиторий для мониторинга PR (fallback, если у задач репозиторий не заполнен)
    private final String monitorRepo;

    public PrCommentMonitor(
            GitHubService github,
            AgentGraphRunner graphRunner,
            TelegramGateway telegram,
            ChatMemoryService chatMemory,
            ActiveTaskRegistry taskRegistry,
            TaskRepository taskRepo,
            ProcessedPrCommentRepository processedComments,
            @Value("${telegram.allowed-chat-ids:}") String allowedChatIds,
            @Value("${github.monitor-repo:}") String monitorRepo
    ) {
        this.github = github;
        this.graphRunner = graphRunner;
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
            List<GitHubService.PrComment> comments = github.listPrComments(repo, pr.number());
            if (comments.isEmpty()) {
                return;
            }

            for (GitHubService.PrComment comment : comments) {
                if (processedComments.existsById(comment.id())) {
                    continue;
                }

                // Пропускаем пустые комментарии
                if (comment.body() == null || comment.body().isBlank()) {
                    markProcessed(comment.id(), pr.number(), repo);
                    continue;
                }

                log.info("Новый комментарий в PR #{} ({}) от {}: {}",
                        pr.number(), repo, comment.author(),
                        comment.body().length() > 100 ? comment.body().substring(0, 100) + "..." : comment.body());

                // Уведомление в ТГ
                long notifyId = resolveNotifyChatId(null, pr.headBranch());
                if (notifyId > 0) {
                    String msg = "💬 Новый комментарий в PR #%d (%s)\nОт: %s\n%s".formatted(
                            pr.number(), pr.htmlUrl(), comment.author(),
                            comment.body().length() > 500 ? comment.body().substring(0, 500) + "..." : comment.body());
                    telegram.sendMessage(notifyId, msg);
                    chatMemory.recordBotMessage(notifyId, msg);
                }

                // Запуск агентного графа с инструкцией из комментария
                launchFromPrComment(repo, pr, comment);

                markProcessed(comment.id(), pr.number(), repo);
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

    private void launchFromPrComment(String repo, GitHubService.PrInfo pr, GitHubService.PrComment comment) {
        String taskId = "pr-" + pr.number() + "-" + comment.id();
        String instruction = """
                Комментарий в PR #%d от %s:
                %s

                Ветка: %s
                URL PR: %s
                """.formatted(pr.number(), comment.author(), comment.body(), pr.headBranch(), pr.htmlUrl());

        executor.submit(() -> {
            try {
                var ctx = AgentContext.of(instruction)
                        .with(TaskState.TASK_ID, taskId)
                        .with(TaskState.TG_CHAT_ID, String.valueOf(resolveNotifyChatId(taskId, pr.headBranch())))
                        .with(TaskState.GIT_BRANCH, pr.headBranch())
                        .with(TaskState.TARGET_REPO, repo)
                        .with(TaskState.REWORK_COUNT, 0);

                AgentResult result = graphRunner.run(ctx);

                String resultMsg;
                if (!result.hasError()) {
                    resultMsg = "✅ Задача по комментарию PR #" + pr.number() + " завершена.";
                } else {
                    resultMsg = "❌ Задача по комментарию PR #" + pr.number() + " не завершена: " + result.error();
                }

                long notifyId = resolveNotifyChatId(taskId, pr.headBranch());
                if (notifyId > 0) {
                    telegram.sendMessage(notifyId, resultMsg);
                    chatMemory.recordBotMessage(notifyId, resultMsg);
                }
            } catch (Exception e) {
                log.error("Ошибка выполнения задачи по комментарию PR #{}: {}", pr.number(), e.getMessage(), e);
            }
        });
    }
}
