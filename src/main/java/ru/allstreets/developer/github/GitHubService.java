package ru.allstreets.developer.github;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Сервис для работы с GitHub через REST API.
 * Создание веток и pull requests.
 * Использует GitHub Personal Access Token.
 */
@Component
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);

    private final RestClient api;

    @Getter
    @Value("${skills.repo-url:}")
    private String skillsRepoUrl;

    @Getter
    @Setter
    @Value("${skills.repo-branch:main}")
    private String skillsBaseBranch;

    @Value("${github.pr-label:agent-generated}")
    private String prLabel;

    @Value("${github.bot-login:}")
    private String botLogin;

    /**
     * Получить все открытые PR с меткой prLabel (созданные агентом).
     * Если botLogin задан — фильтрует по автору.
     *
     * @return список PR (number, title, headBranch, htmlUrl)
     */
    @CircuitBreaker(name = "github")
    @Retry(name = "github")
    public List<PrInfo> listAgentPullRequests(String repo) {
        log.debug("Получение открытых PR с меткой '{}' в {}", prLabel, repo);

        JsonNode prs = api.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/" + repo + "/pulls")
                        .queryParam("state", "open")
                        .queryParam("sort", "updated")
                        .queryParam("direction", "desc")
                        .queryParam("per_page", 30)
                        .build())
                .retrieve()
                .body(JsonNode.class);

        if (prs == null || !prs.isArray()) {
            return List.of();
        }

        List<PrInfo> result = new java.util.ArrayList<>();
        for (JsonNode pr : prs) {
            String author = pr.path("user").path("login").asText();
            String headBranch = pr.path("head").path("ref").asText();

            // Фильтр: по метке или по автору (botLogin)
            boolean matchByLabel = false;
            JsonNode labels = pr.path("labels");
            if (labels != null && labels.isArray()) {
                for (JsonNode label : labels) {
                    if (prLabel.equals(label.path("name").asText())) {
                        matchByLabel = true;
                        break;
                    }
                }
            }

            boolean matchByAuthor = botLogin != null && !botLogin.isBlank() && botLogin.equals(author);

            if (matchByLabel || matchByAuthor) {
                result.add(new PrInfo(
                        pr.path("number").asInt(),
                        pr.path("title").asText(),
                        headBranch,
                        pr.path("html_url").asText(),
                        author,
                        pr.path("updated_at").asText()
                ));
            }
        }

        log.debug("Найдено {} agent PR в {}", result.size(), repo);
        return result;
    }

    /**
     * Получить комментарии к PR (issue comments + review comments).
     *
     * @param repo     репозиторий в формате owner/name
     * @param prNumber номер PR
     * @return список комментариев
     */
    @CircuitBreaker(name = "github")
    @Retry(name = "github")
    public List<PrComment> listPrComments(String repo, int prNumber) {
        log.debug("Получение комментариев для PR #{} в {}", prNumber, repo);

        List<PrComment> result = new java.util.ArrayList<>();

        // Issue comments (основные комментарии на PR)
        JsonNode issueComments = api.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/" + repo + "/issues/{number}/comments")
                        .queryParam("per_page", 100)
                        .build(prNumber))
                .retrieve()
                .body(JsonNode.class);

        if (issueComments != null && issueComments.isArray()) {
            for (JsonNode c : issueComments) {
                result.add(new PrComment(
                        c.path("id").asLong(),
                        c.path("user").path("login").asText(),
                        c.path("body").asText(),
                        c.path("created_at").asText(),
                        c.path("html_url").asText()
                ));
            }
        }

        // Review comments (inline комментарии к коду)
        JsonNode reviewComments = api.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/" + repo + "/pulls/{number}/comments")
                        .queryParam("per_page", 100)
                        .build(prNumber))
                .retrieve()
                .body(JsonNode.class);

        if (reviewComments != null && reviewComments.isArray()) {
            for (JsonNode c : reviewComments) {
                result.add(new PrComment(
                        c.path("id").asLong(),
                        c.path("user").path("login").asText(),
                        c.path("body").asText(),
                        c.path("created_at").asText(),
                        c.path("html_url").asText()
                ));
            }
        }

        log.debug("Найдено {} комментариев для PR #{}", result.size(), prNumber);
        return result;
    }

    // ─── Records для PR данных ───

    public record PrInfo(int number, String title, String headBranch, String htmlUrl,
                         String author, String updatedAt) {
    }

    public record PrComment(long id, String author, String body, String createdAt, String htmlUrl) {
    }

    public GitHubService(@Value("${github.token}") String token) {
        this.api = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .build();
    }
}
