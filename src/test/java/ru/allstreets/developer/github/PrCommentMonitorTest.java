package ru.allstreets.developer.github;

import org.junit.jupiter.api.Test;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.config.AgentGraphRunner;
import ru.allstreets.developer.telegram.ActiveTaskRegistry;
import ru.allstreets.developer.telegram.ChatMemoryService;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Монитор PR-комментариев должен опрашивать ЦЕЛЕВОЙ репозиторий задачи (инцидент 15.09:
 * смотрел f4ky0d4k1/developer, а PR был в iamponamarev/allstreets-spring) и не запускать
 * задачу повторно на уже обработанный комментарий (persistent-дедуп между рестартами).
 */
class PrCommentMonitorTest {

    private static final String REPO = "iamponamarev/allstreets-spring";

    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final GitHubService github = mock(GitHubService.class);
    private final AgentGraphRunner graphRunner = mock(AgentGraphRunner.class);
    private final ProcessedPrCommentRepository processed = mock(ProcessedPrCommentRepository.class);

    private PrCommentMonitor monitor(String fallbackRepo) {
        return new PrCommentMonitor(
                github, graphRunner, mock(TelegramGateway.class), mock(ChatMemoryService.class),
                mock(ActiveTaskRegistry.class), taskRepo, processed, "1", fallbackRepo);
    }

    private void onePrWithComment(long commentId) {
        when(taskRepo.findDistinctRepos(20)).thenReturn(List.of(REPO));
        when(github.listAgentPullRequests(REPO)).thenReturn(List.of(
                new GitHubService.PrInfo(29, "t", "feature/BACKEND-437", "https://x/29", "bot", "now")));
        when(github.listPrComments(REPO, 29)).thenReturn(List.of(
                new GitHubService.PrComment(commentId, "dima", "переделай", "now", "https://x/29#c")));
    }

    @Test
    void includesTaskReposFirst_thenFallback() {
        when(taskRepo.findDistinctRepos(20)).thenReturn(List.of(REPO));

        assertEquals(List.of(REPO, "f4ky0d4k1/developer"),
                monitor("f4ky0d4k1/developer").reposToMonitor());
    }

    @Test
    void dedupsFallbackAlreadyInTasks() {
        when(taskRepo.findDistinctRepos(20)).thenReturn(List.of("owner/repo"));

        assertEquals(List.of("owner/repo"), monitor("owner/repo").reposToMonitor());
    }

    @Test
    void noTasksAndNoFallback_isEmpty() {
        when(taskRepo.findDistinctRepos(20)).thenReturn(List.of());

        assertTrue(monitor("").reposToMonitor().isEmpty());
    }

    @Test
    void alreadyProcessedComment_isNotLaunchedAgain() {
        onePrWithComment(1L);
        when(processed.existsById(1L)).thenReturn(true);

        monitor("").monitorPullRequests();

        verify(processed, never()).save(any());
        verifyNoInteractions(graphRunner);
    }

    @Test
    void newComment_isPersistedAndLaunched() {
        onePrWithComment(7L);
        when(processed.existsById(7L)).thenReturn(false);

        monitor("").monitorPullRequests();

        verify(processed).save(any(ProcessedPrCommentEntity.class));
        verify(graphRunner, org.mockito.Mockito.timeout(2000)).run(any());
    }
}
