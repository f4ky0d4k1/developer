package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Промпт fast-router'а обязан знать про мониторинг PR-комментариев: иначе бот отвечает
 * «автозапуска по комментариям в PR нет» (инцидент 15.09.2026), хотя {@code PrCommentMonitor}
 * существует. Тест — страж от потери этой секции.
 */
class ConversationFastPromptTest {

    @Test
    void fastPrompt_mentionsPrCommentMonitoring() throws Exception {
        var resource = new DefaultResourceLoader().getResource("classpath:prompts/conversation-fast.md");
        String prompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(prompt.contains("Мониторинг PR"),
                "промпт fast-router'а должен описывать мониторинг PR-комментариев");
        assertTrue(prompt.contains("agent-generated"),
                "промпт должен связывать мониторинг с меткой agent-generated");
    }

    @Test
    void fastPrompt_mentionsAllChatsScenario() throws Exception {
        var resource = new DefaultResourceLoader().getResource("classpath:prompts/conversation-fast.md");
        String prompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(prompt.contains("всех чатах"),
                "промпт должен описывать сценарий «задачи из всех чатов»");
        assertTrue(prompt.contains("allChats"),
                "промпт должен упоминать параметр allChats инструмента getChatTasks");
    }

    @Test
    void fastPrompt_trackerTicketDoesNotImplyRepo() throws Exception {
        // Инцидент: пользователь дал только тикет Tracker (URL/ключ), а классификатор «угадал»
        // репозиторий из getChatProjects — тикет относился к чужому проекту. Тикет без явного repo
        // НЕ должен выводить репозиторий из памяти чата.
        var resource = new DefaultResourceLoader().getResource("classpath:prompts/conversation-fast.md");
        String prompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(prompt.contains("тикет Tracker"),
                "промпт должен явно отделять тикет Tracker от определения репозитория");
        assertTrue(prompt.contains("НЕ подставляй репозиторий"),
                "промпт должен запрещать подставлять репозиторий из чата для тикета Tracker");
    }

    @Test
    void fastPrompt_checksUnclosedTasksBeforeLaunch() throws Exception {
        // Оркестратор должен ДО запуска новой задачи проверить незакрытые задачи чата и, если тема
        // совпадает, дополнить существующую (restartTask) вместо создания новой (launch_task).
        var resource = new DefaultResourceLoader().getResource("classpath:prompts/conversation-fast.md");
        String prompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(prompt.contains("незакрытые задачи чата"),
                "промпт должен требовать проверки незакрытых задач перед запуском новой");
        assertTrue(prompt.contains("status=RUNNING"),
                "промпт должен указывать проверять задачи со статусом RUNNING");
        assertTrue(prompt.contains("restartTask(taskId, additionalContext"),
                "промпт должен направлять на дополнение существующей задачи (restartTask) вместо новой");
    }
}
