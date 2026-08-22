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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Мониторинг комментариев в agent-generated PR.
 * Периодически опрашивает GitHub на предмет новых комментариев.
 * При обнаружении нового комментария запускает агентный граф с инструкцией из комментария.
 * <p>
 * Фильтрация: обрабатываются комментарии от любого пользователя (включая bot-login),
 * но только ещё не обработанные (по ID комментария).
 */
@Component
public class PrCommentMonitor {

    private static final Logger log = LoggerFactory.getLogger(PrCommentMonitor.class);

    private final GitHubService github;
    private final AgentGraphRunner graphRunner;
    private final TelegramGateway telegram;
    private final ChatMemoryService chatMemory;
    private final ActiveTaskRegistry taskRegistry;
    private final TaskRepository taskRepo;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // Отслеживание обработанных комментариев: commentId → prNumber
    private final Map<Long, Integer> processedComments = new ConcurrentHashMap<>();

    // fallback chatId для уведомлений (берётся из whitelist — первый)
    private final long fallbackNotifyChatId;

    // Репозиторий для мониторинга PR (настройка мониторинга, не задачи агента)
    private final String monitorRepo;

    public PrCommentMonitor(
            GitHubService github,
            AgentGraphRunner graphRunner,
            TelegramGateway telegram,
            ChatMemoryService chatMemory,
            ActiveTaskRegistry taskRegistry,
            TaskRepository taskRepo,
            @Value("${telegram.allowed-chat-ids:}") String allowedChatIds,
            @Value("${github.monitor-repo:}") String monitorRepo
    ) {
        this.github = github;
        this.graphRunner = graphRunner;
        this.telegram = telegram;
        this.chatMemory = chatMemory;
        this.taskRegistry = taskRegistry;
        this.taskRepo = taskRepo;
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
        try {
            List<GitHubService.PrInfo> prs = github.listAgentPullRequests(monitorRepo);
            if (prs.isEmpty()) {
                return;
            }

            log.debug("Мониторинг: найдено {} открытых agent PR", prs.size());

            for (GitHubService.PrInfo pr : prs) {
                processPrComments(pr);
            }
        } catch (Exception e) {
            log.error("Ошибка мониторинга PR: {}", e.getMessage(), e);
        }
    }

    private void processPrComments(GitHubService.PrInfo pr) {
        try {
            List<GitHubService.PrComment> comments = github.listPrComments(monitorRepo, pr.number());
            if (comments.isEmpty()) {
                return;
            }

            for (GitHubService.PrComment comment : comments) {
                if (processedComments.containsKey(comment.id())) {
                    continue;
                }

                // Пропускаем пустые комментарии
                if (comment.body() == null || comment.body().isBlank()) {
                    processedComments.put(comment.id(), pr.number());
                    continue;
                }

                log.info("Новый комментарий в PR #{} от {}: {}",
                        pr.number(), comment.author(),
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
                launchFromPrComment(pr, comment);

                processedComments.put(comment.id(), pr.number());
            }
        } catch (Exception e) {
            log.error("Ошибка обработки комментариев PR #{}: {}", pr.number(), e.getMessage(), e);
        }
    }

    private void launchFromPrComment(GitHubService.PrInfo pr, GitHubService.PrComment comment) {
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
                        .with(TaskState.TARGET_REPO, monitorRepo)
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
