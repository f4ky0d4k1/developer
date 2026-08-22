package ru.allstreets.developer.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.checkpoint.ChatMessageRepository;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.telegram.ActiveTaskRegistry;
import ru.allstreets.developer.telegram.ChatMemoryService;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * MCP tools для Telegram — доступны OpenCode агентам через MCP server.
 * Агенты могут читать историю чата, отправлять сообщения, смотреть активные задачи и pending-вопросы.
 */
@Component
public class TelegramMcpTools {

    private static final Logger log = LoggerFactory.getLogger(TelegramMcpTools.class);

    private final ChatMemoryService chatMemory;
    private final ChatMessageRepository messageRepo;
    private final TelegramGateway telegram;
    private final ActiveTaskRegistry taskRegistry;
    private final HumanInputRegistry humanInputRegistry;

    private static final DateTimeFormatter FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public TelegramMcpTools(ChatMemoryService chatMemory,
                            ChatMessageRepository messageRepo,
                            TelegramGateway telegram,
                            ActiveTaskRegistry taskRegistry,
                            HumanInputRegistry humanInputRegistry) {
        this.chatMemory = chatMemory;
        this.messageRepo = messageRepo;
        this.telegram = telegram;
        this.taskRegistry = taskRegistry;
        this.humanInputRegistry = humanInputRegistry;
    }

    @Tool(description = "Get recent chat history for a Telegram chat. Returns last N messages with role (user/bot), text, timestamp, and optional taskId. Use this to understand conversation context, find previous questions and answers.")
    public String getChatHistory(
            @ToolParam(description = "Telegram chat ID (negative for groups, e.g. -1001506621216)") long chatId,
            @ToolParam(description = "Number of recent messages to return (default 30, max 100)") Integer limit
    ) {
        int n = limit != null ? Math.min(limit, 100) : 30;
        log.info("MCP getChatHistory: chatId={}, limit={}", chatId, n);

        var messages = messageRepo.findByChatIdOrderByCreatedAtDesc(chatId, PageRequest.of(0, n));
        if (messages.isEmpty()) {
            return "No messages found for chat " + chatId;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = messages.size() - 1; i >= 0; i--) {
            var m = messages.get(i);
            sb.append("[").append(FMT.format(m.getCreatedAt())).append("] ");
            sb.append(m.getRole()).append(": ").append(m.getText());
            if (m.getTaskId() != null) {
                sb.append(" [task:").append(m.getTaskId(), 0, Math.min(8, m.getTaskId().length())).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Send a message to a Telegram chat. The message will be visible to the user and recorded in chat history. Use this to send your final answer to the user.")
    public String sendMessage(
            @ToolParam(description = "Telegram chat ID (negative for groups)") long chatId,
            @ToolParam(description = "Message text to send (Markdown supported)") String text
    ) {
        log.info("MCP sendMessage: chatId={}, textLen={}", chatId, text.length());
        try {
            telegram.sendMessage(chatId, text);
            return "Message sent successfully to chat " + chatId;
        } catch (Exception e) {
            return "Failed to send message: " + e.getMessage();
        }
    }

    @Tool(description = "Get all active tasks for a Telegram chat. Returns taskId, status (RUNNING/COMPLETED/FAILED), and description. Use this to check if there are running tasks before launching new ones.")
    public String getActiveTasks(
            @ToolParam(description = "Telegram chat ID") long chatId
    ) {
        log.info("MCP getActiveTasks: chatId={}", chatId);
        var tasks = taskRegistry.getActiveTasks(chatId);
        if (tasks.isEmpty()) {
            return "No active tasks for chat " + chatId;
        }

        StringBuilder sb = new StringBuilder();
        for (var entry : tasks.entrySet()) {
            sb.append("task ").append(entry.getKey(), 0, 8)
                    .append(" → ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Get pending questions from agents that are waiting for user input (Human-In-The-Loop). Returns taskId and the question text. Use this to check if the user's message might be an answer to a pending question.")
    public String getPendingQuestions(
            @ToolParam(description = "Telegram chat ID") long chatId
    ) {
        log.info("MCP getPendingQuestions: chatId={}", chatId);
        var pending = humanInputRegistry.getPendingQuestionsForChat(chatId);
        if (pending.isEmpty()) {
            return "No pending questions for chat " + chatId;
        }

        StringBuilder sb = new StringBuilder();
        for (var entry : pending.entrySet()) {
            sb.append("task ").append(entry.getKey(), 0, 8)
                    .append(" asks: ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Search messages in chat history by keyword. Returns matching messages with timestamp, role, and text. Useful for finding previous discussions about a topic.")
    public String searchMessages(
            @ToolParam(description = "Telegram chat ID") long chatId,
            @ToolParam(description = "Keyword or phrase to search for (case-insensitive)") String keyword,
            @ToolParam(description = "Maximum number of results (default 10, max 50)") Integer maxResults
    ) {
        int max = maxResults != null ? Math.min(maxResults, 50) : 10;
        log.info("MCP searchMessages: chatId={}, keyword='{}', max={}", chatId, keyword, max);

        var results = messageRepo.searchByKeyword(chatId, keyword, PageRequest.of(0, max));
        if (results.isEmpty()) {
            return "No messages found containing '" + keyword + "' in chat " + chatId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" messages:\n");
        for (int i = results.size() - 1; i >= 0; i--) {
            var m = results.get(i);
            sb.append("[").append(FMT.format(m.getCreatedAt())).append("] ");
            sb.append(m.getRole()).append(": ");
            String text = m.getText();
            if (text.length() > 300) {
                text = text.substring(0, 300) + "...";
            }
            sb.append(text);
            if (m.getTaskId() != null) {
                sb.append(" [task:").append(m.getTaskId(), 0, 8).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Get the last N bot messages in a chat. Useful to see what the bot/agents have already answered, to avoid repeating or to continue a conversation.")
    public String getLastBotMessages(
            @ToolParam(description = "Telegram chat ID") long chatId,
            @ToolParam(description = "Number of bot messages to return (default 5, max 20)") Integer limit
    ) {
        int n = limit != null ? Math.min(limit, 20) : 5;
        log.info("MCP getLastBotMessages: chatId={}, limit={}", chatId, n);

        var all = messageRepo.findByChatIdOrderByCreatedAtDesc(chatId, PageRequest.of(0, 100));
        StringBuilder sb = new StringBuilder();
        int found = 0;

        for (int i = all.size() - 1; i >= 0 && found < n; i--) {
            var m = all.get(i);
            if ("bot".equals(m.getRole())) {
                sb.append("[").append(FMT.format(m.getCreatedAt())).append("] ");
                String text = m.getText();
                if (text.length() > 500) {
                    text = text.substring(0, 500) + "...";
                }
                sb.append(text);
                if (m.getTaskId() != null) {
                    sb.append(" [task:").append(m.getTaskId(), 0, 8).append("]");
                }
                sb.append("\n");
                found++;
            }
        }

        if (found == 0) {
            return "No bot messages found in chat " + chatId;
        }
        return sb.toString();
    }

    @Tool(description = "Get the last N user messages in a chat. Useful to see what users have been asking about recently.")
    public String getLastUserMessages(
            @ToolParam(description = "Telegram chat ID") long chatId,
            @ToolParam(description = "Number of user messages to return (default 5, max 20)") Integer limit
    ) {
        int n = limit != null ? Math.min(limit, 20) : 5;
        log.info("MCP getLastUserMessages: chatId={}, limit={}", chatId, n);

        var all = messageRepo.findByChatIdOrderByCreatedAtDesc(chatId, PageRequest.of(0, 100));
        StringBuilder sb = new StringBuilder();
        int found = 0;

        for (int i = all.size() - 1; i >= 0 && found < n; i--) {
            var m = all.get(i);
            if ("user".equals(m.getRole())) {
                sb.append("[").append(FMT.format(m.getCreatedAt())).append("] ");
                String text = m.getText();
                if (text.length() > 500) {
                    text = text.substring(0, 500) + "...";
                }
                sb.append(text);
                if (m.getTaskId() != null) {
                    sb.append(" [task:").append(m.getTaskId(), 0, 8).append("]");
                }
                sb.append("\n");
                found++;
            }
        }

        if (found == 0) {
            return "No user messages found in chat " + chatId;
        }
        return sb.toString();
    }

    @Tool(description = "Get full context of a chat: recent history, active tasks, and pending questions. This is a convenience tool that combines multiple queries. Use this when you need full context about a chat.")
    public String getChatContext(
            @ToolParam(description = "Telegram chat ID") long chatId
    ) {
        log.info("MCP getChatContext: chatId={}", chatId);
        StringBuilder sb = new StringBuilder();

        // History (last 15 messages)
        sb.append("=== CHAT HISTORY (last 15) ===\n");
        var history = chatMemory.getHistory(chatId);
        int start = Math.max(0, history.size() - 15);
        for (int i = start; i < history.size(); i++) {
            var m = history.get(i);
            sb.append(m.role()).append(": ").append(m.text());
            if (m.taskId() != null) {
                sb.append(" [task:").append(m.taskId(), 0, 8).append("]");
            }
            sb.append("\n");
        }

        // Active tasks
        sb.append("\n=== ACTIVE TASKS ===\n");
        var tasks = taskRegistry.getActiveTasks(chatId);
        if (tasks.isEmpty()) {
            sb.append("none\n");
        } else {
            tasks.forEach((tid, status) -> sb.append(tid, 0, 8).append(" → ").append(status).append("\n"));
        }

        // Pending questions
        sb.append("\n=== PENDING QUESTIONS ===\n");
        var pending = humanInputRegistry.getPendingQuestionsForChat(chatId);
        if (pending.isEmpty()) {
            sb.append("none\n");
        } else {
            pending.forEach((tid, q) -> sb.append(tid, 0, 8).append(": ").append(q).append("\n"));
        }

        return sb.toString();
    }
}
