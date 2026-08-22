package ru.allstreets.developer.state;

import java.util.List;

public record ValidationReport(
        Status status,
        int total,
        int passed,
        int failed,
        List<Failure> failures,
        List<String> coverageGaps
) {

    public enum Status {
        PASS, FAIL
    }

    public record Failure(
            String test,
            FailureType type,
            String message,
            String details
    ) {
    }

    public enum FailureType {
        CODE_BUG,      // Ошибка в реализации → обратно к разработчику
        LOGIC_BUG,     // Ошибка в ТЗ → обратно к аналитику
        TEST_BUG       // Ошибка в тесте → обратно к тестировщику
    }

    public boolean isPass() {
        return status == Status.PASS;
    }

    public boolean hasCodeBugs() {
        return failures.stream().anyMatch(f -> f.type() == FailureType.CODE_BUG);
    }

    public boolean hasLogicBugs() {
        return failures.stream().anyMatch(f -> f.type() == FailureType.LOGIC_BUG);
    }

    public boolean hasTestBugs() {
        return failures.stream().anyMatch(f -> f.type() == FailureType.TEST_BUG);
    }
}
