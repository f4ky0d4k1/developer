package ru.allstreets.developer.checkpoint;

import java.io.Serializable;

public record TaskChatId(String taskId, Long chatId) implements Serializable {
}
