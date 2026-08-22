package ru.allstreets.developer.telegram;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.checkpoint.ChatMessageEntity;
import ru.allstreets.developer.checkpoint.ChatMessageRepository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sliding window памяти чата — последние N сообщений на chatId.
 * Persist в БД (agent_chat_messages) + in-memory cache для быстрого доступа.
 * Используется ConversationAgent для контекста беседы.
 */
@Component
public class ChatMemoryService {

    private static final int WINDOW_SIZE = 30;

    private final ChatMessageRepository messageRepo;
    private final Map<Long, List<ChatMessage>> chatHistory = new ConcurrentHashMap<>();

    public ChatMemoryService(ChatMessageRepository messageRepo) {
        this.messageRepo = messageRepo;
    }

    public record ChatMessage(String role, String text, String taskId, long timestamp) {
    }

    public void recordUserMessage(long chatId, String text) {
        recordUserMessage(chatId, text, null);
    }

    public void recordUserMessage(long chatId, String text, String taskId) {
        messageRepo.save(new ChatMessageEntity(chatId, "user", text, taskId));
        var history = chatHistory.computeIfAbsent(chatId, k -> new CopyOnWriteArrayList<>());
        history.add(new ChatMessage("user", text, taskId, System.currentTimeMillis()));
        trim(history);
    }

    public void recordBotMessage(long chatId, String text) {
        recordBotMessage(chatId, text, null);
    }

    public void recordBotMessage(long chatId, String text, String taskId) {
        messageRepo.save(new ChatMessageEntity(chatId, "bot", text, taskId));
        var history = chatHistory.computeIfAbsent(chatId, k -> new CopyOnWriteArrayList<>());
        history.add(new ChatMessage("bot", text, taskId, System.currentTimeMillis()));
        trim(history);
    }

    public List<ChatMessage> getHistory(long chatId) {
        var cached = chatHistory.get(chatId);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        return loadFromDb(chatId);
    }

    public String getHistoryText(long chatId) {
        var history = getHistory(chatId);
        if (history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var msg : history) {
            sb.append(msg.role()).append(": ").append(msg.text());
            if (msg.taskId() != null) {
                sb.append(" [task:").append(msg.taskId(), 0, 8).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private List<ChatMessage> loadFromDb(long chatId) {
        var entities = messageRepo.findByChatIdOrderByCreatedAtDesc(chatId, PageRequest.of(0, WINDOW_SIZE));
        var loaded = new java.util.ArrayList<ChatMessage>();
        for (int i = entities.size() - 1; i >= 0; i--) {
            var e = entities.get(i);
            loaded.add(new ChatMessage(e.getRole(), e.getText(), e.getTaskId(),
                    e.getCreatedAt() != null ? e.getCreatedAt().toEpochMilli() : 0));
        }
        chatHistory.put(chatId, new CopyOnWriteArrayList<>(loaded));
        return loaded;
    }

    private void trim(List<ChatMessage> history) {
        while (history.size() > WINDOW_SIZE) {
            history.removeFirst();
        }
    }
}
