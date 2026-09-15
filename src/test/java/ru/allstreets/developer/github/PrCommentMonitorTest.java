package ru.allstreets.developer.github;

import org.junit.jupiter.api.Test;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.telegram.ActiveTaskRegistry;
import ru.allstreets.developer.telegram.ChatMemoryService;
import ru.allstreets.developer.telegram.TaskLauncher;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Комментарий в PR не создаёт новую задачу, а возвращает в работу ИСХОДНУЮ (по ветке PR).
 * Плюс: опрос целевых репозиториев задач (инцидент 15.09) и persistent-дедуп.
 */
class PrCommentMonitorTest {

    private static final String REPO = "iamponamarev/allstreets-spring";
    private static final String BRANCH = "feature/BACKEND-437";
    private static final String TASK_ID = "ec0a2004-1111-2222-3333-444455556666";

    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final GitHubService github = mock(GitHubService.class);
    private final TaskLauncher taskLauncher = mock(TaskLauncher.class);
    private final ProcessedPrCommentRepository processed = mock(ProcessedPrCommentRepository.class);

    private PrCommentMonitor monitor(String fallbackRepo) {
        return new PrCommentMonitor(
                github, taskLauncher, mock(TelegramGateway.class), mock(ChatMemoryService.class),
                mock(ActiveTaskRegistry.class), taskRepo, processed, "1", fallbackRepo);
    }

    private void onePrWithComment(long commentId) {
        when(taskRepo.findDistinctRepos(20)).thenReturn(List.of(REPO));
        when(github.listAgentPullRequests(REPO)).thenReturn(List.of(
                new GitHubService.PrInfo(29, "t", BRANCH, "https://x/29", "bot", "now")));
        when(github.listPrComments(REPO, 29)).thenReturn(List.of(
                new GitHubService.PrComment(commentId, "dima", "переделай", "now", "https://x/29#c")));
    }

    private TaskEntity originalTask() {
        return new TaskEntity(TASK_ID, "COMPLETED", "исходное описание", "Техучётки", 1L);
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
    void alreadyProcessedComment_isNotReworked() {
        onePrWithComment(1L);
        when(processed.existsById(1L)).thenReturn(true);

        monitor("").monitorPullRequests();

        verify(taskLauncher, never()).rework(anyString(), anyLong(), anyString());
        verify(processed, never()).save(any());
    }

    @Test
    void newComment_reworksOriginalTaskByBranch() {
        onePrWithComment(7L);
        when(processed.existsById(7L)).thenReturn(false);
        when(taskRepo.findByGitBranch(BRANCH)).thenReturn(Optional.of(originalTask()));
        when(taskLauncher.rework(anyString(), anyLong(), anyString())).thenReturn(true);

        monitor("").monitorPullRequests();

        verify(taskLauncher).rework(eq(TASK_ID), eq(1L), eq("- dima: переделай"));
        verify(processed).save(any(ProcessedPrCommentEntity.class));
    }

    @Test
    void noOriginalTask_leavesCommentsUnprocessed() {
        onePrWithComment(9L);
        when(processed.existsById(9L)).thenReturn(false);
        when(taskRepo.findByGitBranch(BRANCH)).thenReturn(Optional.empty());

        monitor("").monitorPullRequests();

        verify(taskLauncher, never()).rework(anyString(), anyLong(), anyString());
        verify(processed, never()).save(any());
    }
}
