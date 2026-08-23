package ru.allstreets.developer.opencode;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO прогресса OpenCode агента — читается из БД через TaskProgressRegistry.
 * Используется только для форматирования вывода в MCP tools.
 */
@Data
public class TaskProgress {

    private final String agentName;
    private String currentTool;
    private final List<String> toolCalls = new ArrayList<>();
    private final List<String> recentEvents = new ArrayList<>();
    private long totalTokens;
    private double cost;
    private int stepCount;
    private String lastText;
    private String error;
    private final long startTimeMs;
    private long lastUpdateMs;
    private boolean finished;

    public TaskProgress(String agentName) {
        this.agentName = agentName;
        this.startTimeMs = System.currentTimeMillis();
        this.lastUpdateMs = startTimeMs;
    }

    public long getElapsedSeconds() {
        return (lastUpdateMs - startTimeMs) / 1000;
    }

    public String formatSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("agent: ").append(agentName).append("\n");
        sb.append("status: ").append(finished ? "finished" : "running").append("\n");
        sb.append("steps: ").append(stepCount).append("\n");
        sb.append("tokens: ").append(totalTokens).append("\n");
        sb.append("cost: $").append(String.format("%.4f", cost)).append("\n");
        sb.append("tool_calls: ").append(toolCalls.size()).append("\n");
        if (currentTool != null && !finished) {
            sb.append("current_tool: ").append(currentTool).append("\n");
        }
        sb.append("elapsed: ").append(getElapsedSeconds()).append("s\n");
        if (error != null) {
            sb.append("error: ").append(error).append("\n");
        }
        if (!toolCalls.isEmpty()) {
            sb.append("tools_used: ").append(String.join(", ", toolCalls)).append("\n");
        }
        if (!recentEvents.isEmpty()) {
            sb.append("recent_events:\n");
            for (String e : recentEvents) {
                sb.append("  • ").append(e).append("\n");
            }
        }
        if (lastText != null && !lastText.isBlank()) {
            String text = lastText.length() > 300 ? lastText.substring(0, 300) + "..." : lastText;
            sb.append("last_text: ").append(text).append("\n");
        }
        return sb.toString();
    }
}
