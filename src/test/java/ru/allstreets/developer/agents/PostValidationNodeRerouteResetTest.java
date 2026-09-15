package ru.allstreets.developer.agents;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * После успешной пост-валидации (PR создан) устаревший {@code REROUTE_TARGET} должен
 * сбрасываться, иначе граф уходит по старому таргету (инцидент 0f9e5fa2: сразу после
 * «PR создан» ушёл в tester, потому что в контексте висел rerouteTarget=tester с прошлой
 * блокировки «требуются тесты»).
 */
class PostValidationNodeRerouteResetTest {

    @Test
    void prCreated_clearsStaleRerouteTarget() {
        TaskRepository taskRepo = mock(TaskRepository.class);
        when(taskRepo.findById(anyString())).thenReturn(Optional.empty());

        var node = new PostValidationNode(
                mock(ChatClient.class), mock(ChatClient.class), mock(TelegramGateway.class),
                mock(StructuredOutputHelper.class), taskRepo,
                mock(TestExecutionService.class), mock(PullRequestCreationService.class));

        var decision = new AgentResponses.PostValidationDecision(
                "https://github.com/x/y/pull/29", null, null, "ok");

        var result = node.applyDecision(decision, 1, 1L, "task-1");

        assertEquals("", result.stateUpdates().get(TaskState.REROUTE_TARGET),
                "после PR устаревший REROUTE_TARGET должен быть сброшен");
    }
}
