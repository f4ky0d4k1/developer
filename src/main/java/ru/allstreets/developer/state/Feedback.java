package ru.allstreets.developer.state;

public record Feedback(
        String fromAgent,
        String toAgent,
        String message,
        long timestamp
) {}
