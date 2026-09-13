package ru.allstreets.developer.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.agents.AgentResponses;
import ru.allstreets.developer.agents.StructuredOutputHelper;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.mcp.TaskMcpTools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Conversation Agent — оркестратор группового чата Telegram.
 * <p>
 * Fast mode: deepseek-v4-flash (дешёвая), умный промпт → LAUNCH_TASK / HITL_ANSWER / ANSWER / STATUS.
 * <p>
 * Видит sliding window истории чата и активные задачи с pending-вопросами.
 */
@Component
public class ConversationAgent {

    private static final Logger log = LoggerFactory.getLogger(ConversationAgent.class);

    /**
     * Сколько задач чата кладём в контекст классификатора за раз (остальное — через getChatTasks).
     */
    private static final int TASKS_PAGE_SIZE = 10;

    private final ChatClient fastChatClient;
    private final ChatClient fallbackChatClient;
    private final ChatMemoryService chatMemory;
    private final ActiveTaskRegistry taskRegistry;
    private final HumanInputRegistry humanInputRegistry;
    private final StructuredOutputHelper structuredOutput;
    private final String systemPrompt;
    private final TaskMcpTools taskMcpTools;

    public ConversationAgent(@Qualifier("fastChatClient") ChatClient fastChatClient,
                             @Qualifier("fallbackChatClient") ChatClient fallbackChatClient,
                             ChatMemoryService chatMemory,
                             ActiveTaskRegistry taskRegistry,
                             HumanInputRegistry humanInputRegistry,
                             StructuredOutputHelper structuredOutput,
                             ResourceLoader resourceLoader,
                             TaskMcpTools taskMcpTools) {
        this.fastChatClient = fastChatClient;
        this.fallbackChatClient = fallbackChatClient;
        this.chatMemory = chatMemory;
        this.taskRegistry = taskRegistry;
        this.humanInputRegistry = humanInputRegistry;
        this.structuredOutput = structuredOutput;
        this.systemPrompt = loadSystemPrompt(resourceLoader);
        this.taskMcpTools = taskMcpTools;
    }

    private String loadSystemPrompt(ResourceLoader resourceLoader) {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/conversation-fast.md");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Не удалось загрузить conversation-fast.md: {}", e.getMessage());
            return "";
        }
    }

    public Decision processMessage(long chatId, String username, String messageText) {
        String history = chatMemory.getHistoryText(chatId);
        Page<TaskEntity> taskPage = taskRegistry.getChatTasksPage(chatId, 0, TASKS_PAGE_SIZE);
        var pendingQuestions = humanInputRegistry.getPendingQuestionsForChat(chatId);

        String activeTasksStr = formatTasksPage(taskPage);

        String pendingStr = pendingQuestions.isEmpty() ? "нет pending-вопросов"
                : pendingQuestions.entrySet().stream()
                .map(e -> "task " + e.getKey() + ": " + e.getValue())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("нет pending-вопросов");

        // Pre-fetch: если пользователь упоминает taskId и спрашивает про детали — подтягиваем getTaskDetails
        String taskDetailsPrefetch = prefetchTaskDetails(messageText);

        String contextPrompt = """
                Chat ID: %d (используй для вызова tools)
                
                История чата:
                %s
                
                Активные задачи:
                %s
                
                Pending-вопросы от агентов (ожидают ответа):
                %s
                %s
                Новое сообщение от пользователя %s:
                %s
                """.formatted(chatId, history, activeTasksStr, pendingStr,
                taskDetailsPrefetch != null ? taskDetailsPrefetch + "\n" : "",
                username, messageText);

        log.info("ConversationAgent [fast]: chatId={}, history={} сообщений, pending={} вопросов",
                chatId, chatMemory.getHistory(chatId).size(), pendingQuestions.size());

        try {
            // .entity() с tools — native structured output + tool calling
            AgentResponses.FastDecision fastResult;
            try {
                fastResult = fastChatClient.prompt()
                        .user(contextPrompt)
                        .call()
                        .entity(AgentResponses.FastDecision.class);
            } catch (Exception e) {
                // .entity() failed — tool error, JSON parse, network etc — fallback без tools
                log.warn("ConversationAgent [fast]: .entity() failed: {} | {}, fallback без tools",
                        e.getClass().getSimpleName(), e.getMessage());
                return structuredOutputFallback(contextPrompt);
            }

            if (fastResult == null) {
                // .entity() вернул null — fallback на дешёвую модель
                log.warn("ConversationAgent [fast]: .entity() вернул null, fallback");
                fastResult = structuredOutput.callWithFallback(
                        fallbackChatClient, fallbackChatClient, contextPrompt,
                        AgentResponses.FastDecision.class);
            }

            if (fastResult == null) {
                log.warn("ConversationAgent [fast]: пустой ответ после fallback");
                return new Decision(AgentResponses.FastAction.ERROR, null, null, "Пустой ответ LLM", null);
            }

            log.info("ConversationAgent [fast]: action={} taskId={} description='{}'",
                    fastResult.action(), fastResult.taskId(),
                    fastResult.description() != null ? (fastResult.description().length() > 80 ? fastResult.description().substring(0, 80) + "..." : fastResult.description()) : "null");

            return new Decision(fastResult.action(),
                    fastResult.taskId() != null ? fastResult.taskId() : "",
                    fastResult.text() != null ? fastResult.text() : "",
                    fastResult.description() != null ? fastResult.description() : "",
                    fastResult.repo() != null ? fastResult.repo() : "");

        } catch (Exception e) {
            log.error("ConversationAgent [fast]: ошибка: {}", e.getMessage(), e);
            return new Decision(AgentResponses.FastAction.ERROR, null, null, "Ошибка LLM: " + e.getMessage(), null);
        }
    }

    private Decision structuredOutputFallback(String prompt) {
        String fullPrompt = systemPrompt + "\n\n" + prompt;
        log.info("ConversationAgent [fast]: structuredOutputFallback, prompt len={}", fullPrompt.length());
        AgentResponses.FastDecision result = structuredOutput.callWithFallback(
                fallbackChatClient, fallbackChatClient, fullPrompt, AgentResponses.FastDecision.class);
        if (result == null) {
            log.error("ConversationAgent [fast]: callWithFallback вернул null — обе модели не смогли дать JSON");
            return new Decision(AgentResponses.FastAction.ERROR, null, null, "Пустой ответ LLM (fallback)", null);
        }
        return new Decision(result.action(),
                result.taskId() != null ? result.taskId() : "",
                result.text() != null ? result.text() : "",
                result.description() != null ? result.description() : "",
                result.repo() != null ? result.repo() : "");
    }

    private static final java.util.regex.Pattern TASK_ID_PATTERN =
            java.util.regex.Pattern.compile("\\b([0-9a-fA-F]{8})\\b");
    private static final java.util.regex.Pattern DETAILS_KEYWORDS =
            java.util.regex.Pattern.compile("подробн|детал|статус|что по|как дела|что там|результат|прогресс", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Если пользователь упоминает taskId (8 hex chars) и спрашивает про детали/статус —
     * pre-fetch getTaskDetails и вставляем в контекст, чтобы LLM не нужно было вызывать tool.
     */
    private String prefetchTaskDetails(String messageText) {
        if (messageText == null || messageText.isBlank()) return null;

        var idMatcher = TASK_ID_PATTERN.matcher(messageText);
        if (!idMatcher.find()) return null;

        if (!DETAILS_KEYWORDS.matcher(messageText).find()) return null;

        String partialId = idMatcher.group(1);
        log.info("ConversationAgent: pre-fetch getTaskDetails для taskId={}", partialId);
        try {
            String details = taskMcpTools.getTaskDetails(partialId);
            if (details != null && !details.startsWith("Task not found")) {
                return "Детали задачи " + partialId + " (pre-fetched):\n" + details;
            }
        } catch (Exception e) {
            log.warn("ConversationAgent: pre-fetch getTaskDetails failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Форматирует страницу задач чата для контекста классификатора — без N+1.
     */
    private static String formatTasksPage(Page<TaskEntity> page) {
        if (page.isEmpty()) {
            return "нет задач";
        }
        StringBuilder sb = new StringBuilder();
        for (TaskEntity t : page.getContent()) {
            sb.append(shortId(t.getTaskId())).append(" → ").append(t.getStatus());
            String title = t.getTitle();
            if (title != null && !title.isBlank()) {
                sb.append(" — ").append(title);
            }
            if (t.getCreatedAt() != null) {
                sb.append(" (").append(t.getCreatedAt().toString(), 0, 16).append(")");
            }
            sb.append("\n");
        }
        long more = page.getTotalElements() - page.getNumberOfElements();
        if (more > 0) {
            sb.append("(+").append(more).append(" more — вызови getChatTasks(chatId, page, perPage))\n");
        }
        return sb.toString();
    }

    private static String shortId(String id) {
        return id != null && id.length() > 8 ? id.substring(0, 8) : id;
    }

    public record Decision(AgentResponses.FastAction action, String taskId, String text, String description,
                           String repo) {
    }
}
