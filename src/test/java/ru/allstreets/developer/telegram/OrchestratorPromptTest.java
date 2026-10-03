package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Промпт оркестратора обязан знать про мониторинг PR-комментариев: иначе бот отвечает
 * «автозапуска по комментариям в PR нет» (инцидент 15.09.2026), хотя {@code PrCommentMonitor}
 * существует. Тест — страж от потери этой секции.
 */
class OrchestratorPromptTest {

    @Test
    void orchestratorPrompt_mentionsPrCommentMonitoring() throws Exception {
        var resource = new DefaultResourceLoader().getResource("classpath:prompts/orchestrator.md");
        String prompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(prompt.contains("Мониторинг PR"),
                "промпт оркестратора должен описывать мониторинг PR-комментариев");
        assertTrue(prompt.contains("agent-generated"),
                "промпт должен связывать мониторинг с меткой agent-generated");
    }

    @Test
    void orchestratorPrompt_mentionsAllChatsScenario() throws Exception {
        var resource = new DefaultResourceLoader().getResource("classpath:prompts/orchestrator.md");
        String prompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(prompt.contains("всех чатах"),
                "промпт должен описывать сценарий «задачи из всех чатов»");
        assertTrue(prompt.contains("allChats"),
                "промпт должен упоминать параметр allChats инструмента getChatTasks");
    }

    @Test
    void orchestratorPrompt_trackerTicketDoesNotImplyRepo() throws Exception {
        // Инцидент: пользователь дал только тикет Tracker (URL/ключ), а классификатор «угадал»
        // репозиторий из getChatProjects — тикет относился к чужому проекту. Тикет без явного repo
        // НЕ должен выводить репозиторий из памяти чата.
        var resource = new DefaultResourceLoader().getResource("classpath:prompts/orchestrator.md");
        String prompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(prompt.contains("тикет Tracker"),
                "промпт должен явно отделять тикет Tracker от определения репозитория");
        assertTrue(prompt.contains("НЕ подставляй репозиторий"),
                "промпт должен запрещать подставлять репозиторий из чата для тикета Tracker");
    }

    @Test
    void orchestratorPrompt_checksUnclosedTasksBeforeLaunch() throws Exception {
        // Оркестратор должен ДО запуска новой задачи проверить незакрытые задачи чата и, если тема
        // совпадает, дополнить существующую (restartTask) вместо создания новой (launch_task).
        var resource = new DefaultResourceLoader().getResource("classpath:prompts/orchestrator.md");
        String prompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(prompt.contains("незакрытые задачи чата"),
                "промпт должен требовать проверки незакрытых задач перед запуском новой");
        assertTrue(prompt.contains("status=RUNNING"),
                "промпт должен указывать проверять задачи со статусом RUNNING");
        assertTrue(prompt.contains("restartTask(taskId, additionalContext"),
                "промпт должен направлять на дополнение существующей задачи (restartTask) вместо новой");
    }

    @Test
    void orchestratorPrompt_requiresExplicitInstructionBeforeLaunch() throws Exception {
        // Оркестратор не должен запускать задачу по одному лишь описанию потребности/вопросу —
        // только по явной команде («сделай», «реализуй», …). Инцидент: «мне нужно уметь конвертировать
        // HEIC» запускало задачу сразу, без команды.
        var resource = new DefaultResourceLoader().getResource("classpath:prompts/orchestrator.md");
        String prompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(prompt.contains("НЕ приступай к разработке"),
                "промпт должен запрещать разработку без явного указания");
        assertTrue(prompt.contains("ЯВНО не попросил"),
                "промпт должен требовать явной команды пользователя");
    }

    @Test
    void orchestratorPrompt_neverOpensNewTaskWhenActiveOnSameTopic() throws Exception {
        // Дубликат-задача по той же теме не должна создаваться, если есть незакрытая задача.
        var resource = new DefaultResourceLoader().getResource("classpath:prompts/orchestrator.md");
        String prompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(prompt.contains("НИКОГДА не открывай новую задачу"),
                "промпт должен жёстко запрещать новую задачу при активной по той же теме");
        assertTrue(prompt.contains("незакрытая задача по той же теме"),
                "промпт должен связывать запрет с незакрытой задачей по той же теме");
    }
}
