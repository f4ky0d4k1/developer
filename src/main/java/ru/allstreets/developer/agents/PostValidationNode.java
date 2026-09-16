package ru.allstreets.developer.agents;

import io.github.asekka.springai.agents.core.Agent;
import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentError;
import io.github.asekka.springai.agents.core.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.List;

/**
 * Post-Validation — LLM-driven контроль результата задачи.
 * <p>
 * Тесты здесь НЕ запускаются: их пишет и гоняет агент {@code tester}, доводит до зелёного
 * {@code developer} (у обоих есть полное окружение). Узел только:
 * <ol>
 *   <li>fail-fast границы: required-разработка/тесты не завершены → возврат к developer/tester;</li>
 *   <li>вызывает агента {@code validator} с полным контекстом задачи — тот инспектирует worktree
 *       и решает: создать PR либо вернуть к analyst/tester/developer (LLM-driven, куда угодно).</li>
 * </ol>
 */
@Component
public class PostValidationNode implements Agent {

    private static final Logger log = LoggerFactory.getLogger(PostValidationNode.class);

    /**
     * Ограничение на длину вырезаемого куска контекста в промпте валидатора.
     */
    private static final int SPEC_LIMIT = 3000;

    private final ChatClient chatClient;
    private final ChatClient fallbackChatClient;
    private final TelegramGateway telegram;
    private final StructuredOutputHelper structuredOutput;
    private final TaskRepository taskRepo;
    private final ValidatorService validator;
    private final String prLabel;
    private final String baseBranch;
    private final SlotUnavailableHandler slotHandler;

    public PostValidationNode(@Qualifier("postValidationChatClient") ChatClient chatClient,
                              @Qualifier("fallbackChatClient") ChatClient fallbackChatClient,
                              TelegramGateway telegram, StructuredOutputHelper structuredOutput,
                              TaskRepository taskRepo, ValidatorService validator,
                              @Value("${github.pr-label:agent-generated}") String prLabel,
                              @Value("${github.base-branch:Atest}") String baseBranch,
                              SlotUnavailableHandler slotHandler) {
        this.chatClient = chatClient;
        this.fallbackChatClient = fallbackChatClient;
        this.telegram = telegram;
        this.structuredOutput = structuredOutput;
        this.taskRepo = taskRepo;
        this.validator = validator;
        this.prLabel = prLabel;
        this.baseBranch = baseBranch;
        this.slotHandler = slotHandler;
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        Integer reworkCountRaw = ctx.get(TaskState.REWORK_COUNT);
        int reworkCount = reworkCountRaw != null ? reworkCountRaw : 0;
        String chatId = ctx.get(TaskState.TG_CHAT_ID);
        String branch = ctx.get(TaskState.GIT_BRANCH);
        String spec = ctx.get(TaskState.SPEC);
        String agentRole = ctx.get(TaskState.AGENT_ROLE);
        String nextStep = ctx.get(TaskState.NEXT_STEP);

        log.info("Post-validation: start. reworkCount={}, branch={}, agentRole={}, nextStep={}",
                reworkCount, branch, agentRole, nextStep);

        if (chatId == null || chatId.isBlank()) {
            log.warn("Post-validation: нет chatId в контексте, пропуск (stale checkpoint)");
            return skipResult();
        }

        long chatIdLong = Long.parseLong(chatId);
        String targetRepo = ctx.get(TaskState.TARGET_REPO);
        String repoUrl = toRepoUrl(targetRepo);

        if (isAnalysisOnly(agentRole, nextStep)) {
            return handleAnalysisOnly(spec);
        }

        String taskId = ctx.get(TaskState.TASK_ID);

        // Fail-fast границы: PR нельзя выпускать, если required-этапы не завершены.
        if (isRequiredNotDone(ctx, TaskState.REQUIRES_DEVELOPMENT, TaskState.DEVELOPMENT_DONE)) {
            log.warn("Post-validation: требуется разработка, но developmentDone=false. Возврат к developer.");
            return reroute(chatIdLong, "developer", reworkCount,
                    "❌ Требуется разработка, но она не выполнена. Возврат к разработчику.", taskId);
        }
        if (isRequiredNotDone(ctx, TaskState.REQUIRES_TESTING, TaskState.TESTS_WRITTEN)) {
            log.warn("Post-validation: требуется тестирование, но testsWritten=false. Возврат к tester.");
            return reroute(chatIdLong, "tester", reworkCount,
                    "❌ Требуются тесты, но они не написаны. Возврат к тестировщику.", taskId);
        }

        telegram.sendMessage(chatIdLong, "🔍 Post-validation: валидатор проверяет результат...", taskId);
        String openCodeOutput;
        try {
            openCodeOutput = validator.run(buildValidatorPrompt(ctx, reworkCount), chatIdLong, repoUrl, taskId);
        } catch (ru.allstreets.developer.opencode.SlotUnavailableException e) {
            return slotHandler.askToFreeSlots(taskId, chatIdLong, "validator");
        }
        if (openCodeOutput == null) {
            return AgentResult.failed(AgentError.of("post_validation",
                    new RuntimeException("Ошибка OpenCode при валидации")));
        }

        AgentResponses.PostValidationDecision decision = parseDecision(openCodeOutput, chatIdLong, taskId);
        if (decision == null) {
            return AgentResult.failed(AgentError.of("post_validation",
                    new RuntimeException("Пустой ответ LLM")));
        }

        return applyDecision(decision, reworkCount, chatIdLong, taskId);
    }

    private static boolean isRequiredNotDone(AgentContext ctx,
                                             io.github.asekka.springai.agents.core.StateKey<Boolean> requiredKey,
                                             io.github.asekka.springai.agents.core.StateKey<Boolean> doneKey) {
        boolean required = Boolean.TRUE.equals(ctx.get(requiredKey));
        boolean done = Boolean.TRUE.equals(ctx.get(doneKey));
        return required && !done;
    }

    private AgentResult reroute(long chatIdLong, String target, int reworkCount, String message, String taskId) {
        telegram.sendMessage(chatIdLong, message, taskId);
        int newReworkCount = reworkCount + 1;
        log.info("Post-validation: возврат к узлу '{}' (reworkCount={})", target, newReworkCount);
        return AgentResult.builder()
                .text(message)
                .stateUpdates(java.util.Map.of(
                        TaskState.REWORK_COUNT, newReworkCount,
                        TaskState.REROUTE_TARGET, target,
                        TaskState.AGENT_ROLE, "post_validation"))
                .completed(true)
                .build();
    }

    private AgentResult skipResult() {
        return AgentResult.builder()
                .text("Skipped: no chat context")
                .stateUpdates(java.util.Map.of(
                        TaskState.AGENT_ROLE, "post_validation"))
                .completed(true)
                .build();
    }

    private boolean isAnalysisOnly(String agentRole, String nextStep) {
        if (!"analyst".equals(agentRole)) return false;
        return nextStep == null || "done".equals(nextStep);
    }

    private AgentResult handleAnalysisOnly(String spec) {
        log.info("Post-validation: аналитическая задача, пропускаем тесты и PR (ответ уже отправлен аналитиком)");
        String summary = (spec != null && !spec.isBlank()) ? spec : "Готово";
        return AgentResult.builder()
                .text(summary)
                .stateUpdates(java.util.Map.of(
                        TaskState.AGENT_ROLE, "post_validation"))
                .completed(true)
                .build();
    }

    /**
     * Промпт валидатора: полный контекст задачи + границы решения. Валидатор сам решает,
     * куда вернуться (analyst/tester/developer), либо создаёт PR.
     */
    private String buildValidatorPrompt(AgentContext ctx, int reworkCount) {
        var sb = new StringBuilder();
        sb.append("""
                Ты — валидатор. Проверь результат выполнения задачи и прими решение:
                создать Pull Request либо вернуть работу на доработку.
                
                Порядок работы:
                1. Переключись на нужную ветку, если она указана ниже.
                2. Изучи, что реально сделано: `git log --oneline -10`, `git diff main...HEAD`
                   (либо `git status`), прочитай изменённые файлы.
                3. Сверь результат с ТЗ и acceptance criteria.
                4. Тесты НЕ запускай — их уже написали (`tester`) и добились зелёного прогона (`developer`).
                   Если видишь, что тестов нет или покрытие не соответствует AC — это повод вернуть tester.
                5. Решение:
                   - ЦЕЛЕВАЯ ВЕТКА (base) PR: дефолт — тестовая ветка `%s`. ПРОВЕРЬ через GitHub MCP,
                     что она существует в репозитории; если тестовой ветки НЕТ — создавай PR в `main`
                     (продовую). Если пользователь в задании ЯВНО указал другую целевую ветку — её (приоритет).
                   - ПЕРЕД созданием PR проверь, не сделал ли это кто-то раньше: найди PR по твоей
                     head-ветке (GitHub MCP, state=open). Если PR уже есть — НЕ создавай дубликат, а
                     проверь его оформление (base — %s, метка «%s», осмысленные title/body) и исправь,
                     если что-то не так (смени base / повесь метку). Если PR нет — создай новый
                     через GitHub MCP;
                   - не хватает реализации → reroute "developer";
                   - не хватает/неверны тесты → reroute "tester";
                   - проблема в ТЗ/требованиях, нужен пересмотр → reroute "analyst";
                   - задача ВЫПОЛНЕНА, но PR не нужен: правки только в Трекере, документации или
                     иных местах без изменений кода (PR создавать нечего и не из чего) → done;
                   - задача невыполнима → failed.
                6. В Трекер НЕ пиши: текст в Трекере (отчёты, итоги, описание доработок) ведёт агент
                   reporter, его запускает аналитик. Задачу Трекера не создавай и не связывай — это
                   дело аналитика. Что стоит зафиксировать в тикете — сформулируй в поле summary.
                
                В конце ответа выведи СТРОГО JSON:
                ```json
                {
                  "prUrl": "https://.../pull/N — если PR создан, иначе null",
                  "done": "кратко: что сделано и почему PR не требуется — иначе null",
                  "reroute": "analyst | tester | developer — если нужна доработка, иначе null",
                  "failed": "причина — если задача невыполнима, иначе null",
                  "summary": "кратко: что сделано и почему такое решение"
                }
                ```
                """.formatted(baseBranch, baseBranch, prLabel));

        sb.append("\n## Контекст задачи\n");
        appendSection(sb, "ТЗ / спека", ctx.get(TaskState.SPEC), SPEC_LIMIT);
        appendSection(sb, "User Story", ctx.get(TaskState.USER_STORY), 1000);
        appendSection(sb, "Acceptance Criteria", join(ctx.get(TaskState.ACCEPTANCE_CRITERIA)), 2000);
        appendSection(sb, "Out of scope", join(ctx.get(TaskState.OUT_OF_SCOPE)), 1000);
        appendSection(sb, "Constraints", join(ctx.get(TaskState.CONSTRAINTS)), 1000);
        appendSection(sb, "Context links", join(ctx.get(TaskState.CONTEXT_LINKS)), 1000);
        appendSection(sb, "План тестов", ctx.get(TaskState.TEST_PLAN), 1500);
        appendSection(sb, "Что реализовано", ctx.get(TaskState.IMPLEMENTATION), 2000);
        appendSection(sb, "Ветка", ctx.get(TaskState.GIT_BRANCH), 200);
        appendSection(sb, "Последний коммит", ctx.get(TaskState.COMMIT_HASH), 200);

        sb.append("\n## Статус выполнения\n");
        sb.append("- requiresDevelopment: ").append(Boolean.TRUE.equals(ctx.get(TaskState.REQUIRES_DEVELOPMENT))).append("\n");
        sb.append("- developmentDone: ").append(Boolean.TRUE.equals(ctx.get(TaskState.DEVELOPMENT_DONE))).append("\n");
        sb.append("- requiresTesting: ").append(Boolean.TRUE.equals(ctx.get(TaskState.REQUIRES_TESTING))).append("\n");
        sb.append("- testsWritten: ").append(Boolean.TRUE.equals(ctx.get(TaskState.TESTS_WRITTEN))).append("\n");
        sb.append("- prCreated: ").append(Boolean.TRUE.equals(ctx.get(TaskState.PR_CREATED))).append("\n");
        sb.append("- итерация доработок: ").append(reworkCount).append("/3\n");

        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String title, String value, int maxLen) {
        if (value == null || value.isBlank()) return;
        sb.append("\n### ").append(title).append(":\n").append(truncate(value, maxLen)).append("\n");
    }

    private static String join(List<String> items) {
        if (items == null || items.isEmpty()) return null;
        return String.join("\n- ", items);
    }

    private static String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private AgentResponses.PostValidationDecision parseDecision(String openCodeOutput, long chatIdLong, String taskId) {
        try {
            String parsePrompt = """
                    Извлеки JSON из ответа post-validation и верни как structured output.
                    Если поле отсутствует — верни null.
                    
                    Ответ:
                    %s
                    """.formatted(openCodeOutput);

            var decision = structuredOutput.callWithFallback(
                    chatClient, fallbackChatClient, parsePrompt, AgentResponses.PostValidationDecision.class);

            if (decision == null) {
                log.error("Post-validation: пустой ответ LLM");
                telegram.sendMessage(chatIdLong, "❌ Post-validation: пустой ответ от LLM", taskId);
            }

            return decision;
        } catch (Exception e) {
            log.error("Post-validation: ошибка парсинга: {}", e.getMessage(), e);
            telegram.sendMessage(chatIdLong, "❌ Ошибка post-validation: " + e.getMessage(), taskId);
            return null;
        }
    }

    AgentResult applyDecision(AgentResponses.PostValidationDecision decision, int reworkCount, long chatIdLong, String taskId) {
        log.info("Post-validation: решение — prUrl={}, done={}, reroute={}, failed={}",
                decision.prUrl(), decision.done(), decision.reroute(), decision.failed());
        String summary = decision.summary() != null ? decision.summary() : "";

        if (decision.prUrl() != null && !decision.prUrl().isBlank()) {
            log.info("Post-validation: PR создан — {}", decision.prUrl());
            telegram.sendMessage(chatIdLong, "✅ PR создан: " + decision.prUrl(), taskId);
            taskRepo.findById(taskId).ifPresent(task -> {
                task.setPrCreated(true);
                taskRepo.save(task);
            });
            return AgentResult.builder()
                    .text(decision.prUrl())
                    .stateUpdates(java.util.Map.of(
                            TaskState.AGENT_ROLE, "post_validation",
                            TaskState.PR_CREATED, true,
                            // Сброс: иначе устаревший REROUTE_TARGET (напр. "tester" с прошлой
                            // блокировки «тесты не написаны») уводит граф в старый узел после PR.
                            TaskState.REROUTE_TARGET, ""))
                    .completed(true)
                    .build();
        }

        // Успешное завершение без PR: задача не предполагала изменений кода (правки в Трекере,
        // документации и т.п.). Отдельный исход нужен, чтобы «выполнено» не падало как
        // «решение не определено» (инцидент 427edb3c).
        if (decision.done() != null && !decision.done().isBlank()) {
            String doneMsg = decision.done();
            log.info("Post-validation: задача выполнена без PR — {}", doneMsg);
            telegram.sendMessage(chatIdLong, "✅ " + doneMsg, taskId);
            return AgentResult.builder()
                    .text(doneMsg)
                    .stateUpdates(java.util.Map.of(
                            TaskState.AGENT_ROLE, "post_validation",
                            TaskState.REROUTE_TARGET, ""))
                    .completed(true)
                    .build();
        }

        if (decision.reroute() != null && !decision.reroute().isBlank()) {
            int newReworkCount = reworkCount + 1;
            log.info("Post-validation: LLM решила вернуться к узлу '{}' (reworkCount={})", decision.reroute(), newReworkCount);
            telegram.sendMessage(chatIdLong,
                    "🔄 Возврат к узлу: " + decision.reroute() + " (попытка " + newReworkCount + "/3)", taskId);
            return AgentResult.builder()
                    .text(summary)
                    .stateUpdates(java.util.Map.of(
                            TaskState.REWORK_COUNT, newReworkCount,
                            TaskState.REROUTE_TARGET, decision.reroute(),
                            TaskState.AGENT_ROLE, "post_validation"))
                    .completed(true)
                    .build();
        }

        if (decision.failed() != null && !decision.failed().isBlank()) {
            log.warn("Post-validation: LLM сообщила FAILED — {}", decision.failed());
            telegram.sendMessage(chatIdLong, "❌ " + decision.failed(), taskId);
            return AgentResult.failed(AgentError.of("post_validation",
                    new RuntimeException(decision.failed())));
        }

        // Ни prUrl, ни done, ни reroute, ни failed — решение не определено. Само-возврат убран:
        // отсутствие URL PR при заявленном создании — ошибка, а не повод крутить граф.
        log.warn("Post-validation: LLM не выдала prUrl/done/reroute/failed, summary={}", decision.summary());
        String fallbackMsg = summary.isBlank() ? "Решение не определено" : summary;
        telegram.sendMessage(chatIdLong, "⚠️ Post-validation: " + fallbackMsg, taskId);
        return AgentResult.failed(AgentError.of("post_validation",
                new RuntimeException("Post-validation: решение не определено — " + fallbackMsg)));
    }

    private static String toRepoUrl(String repo) {
        if (repo == null || repo.isBlank()) return null;
        if (repo.startsWith("https://")) return repo.endsWith(".git") ? repo : repo + ".git";
        return "https://github.com/" + repo + ".git";
    }
}
