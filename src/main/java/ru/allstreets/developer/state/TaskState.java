package ru.allstreets.developer.state;

import io.github.asekka.springai.agents.core.StateKey;

import java.util.List;

public final class TaskState {

    private TaskState() {
    }

    // ─── Идентификация задачи ───
    public static final StateKey<String> TASK_ID = StateKey.of("taskId", String.class);
    public static final StateKey<String> TG_CHAT_ID = StateKey.of("tgChatId", String.class);
    public static final StateKey<String> TRACKER_ISSUE = StateKey.of("trackerIssue", String.class);

    // ─── Результаты работы агентов ───
    public static final StateKey<String> SPEC = StateKey.of("spec", String.class);
    public static final StateKey<String> GIT_BRANCH = StateKey.of("gitBranch", String.class);
    public static final StateKey<String> TEST_PLAN = StateKey.of("testPlan", String.class);
    public static final StateKey<String> IMPLEMENTATION = StateKey.of("implementation", String.class);
    public static final StateKey<String> COMMIT_HASH = StateKey.of("commitHash", String.class);
    public static final StateKey<ValidationReport> VALIDATION = StateKey.of("validation", ValidationReport.class);

    // ─── Управление графом ───
    public static final StateKey<Integer> REWORK_COUNT = StateKey.of("reworkCount", Integer.class);
    public static final StateKey<String> REROUTE_TARGET = StateKey.of("rerouteTarget", String.class);
    public static final StateKey<String> NEXT_STEP = StateKey.of("nextStep", String.class);
    @SuppressWarnings("unchecked")
    public static final StateKey<List<Feedback>> FEEDBACK = (StateKey<List<Feedback>>) (StateKey<?>) StateKey.of("feedback", List.class);

    // ─── Роль агента для логирования ───
    public static final StateKey<String> AGENT_ROLE = StateKey.of("agentRole", String.class);

    // ─── Tracking требований задачи ───
    public static final StateKey<Boolean> ANALYSIS_DONE = StateKey.of("analysisDone", Boolean.class);
    public static final StateKey<Boolean> REQUIRES_DEVELOPMENT = StateKey.of("requiresDevelopment", Boolean.class);
    public static final StateKey<Boolean> DEVELOPMENT_DONE = StateKey.of("developmentDone", Boolean.class);
    public static final StateKey<Boolean> PR_CREATED = StateKey.of("prCreated", Boolean.class);
    public static final StateKey<Boolean> REQUIRES_TESTING = StateKey.of("requiresTesting", Boolean.class);
    public static final StateKey<Boolean> TESTS_WRITTEN = StateKey.of("testsWritten", Boolean.class);
    public static final StateKey<Boolean> TESTING_DONE = StateKey.of("testingDone", Boolean.class);
}
