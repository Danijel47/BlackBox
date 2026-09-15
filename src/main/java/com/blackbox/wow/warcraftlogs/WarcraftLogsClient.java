package com.blackbox.wow.warcraftlogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.Serial;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class WarcraftLogsClient {

    private static final Duration DEFAULT_RATE_LIMIT_BACKOFF = Duration.ofHours(1);
    private static final long MAX_RATE_LIMIT_BACKOFF_SECONDS = Duration.ofHours(24).toSeconds();
    private static final String RATE_LIMIT_QUERY = """
            query RateLimit {
              rateLimitData {
                limitPerHour
                pointsSpentThisHour
                pointsResetIn
              }
            }
            """;

    private final WarcraftLogsProperties properties;
    private final WarcraftLogsAuthService authService;
    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final AtomicReference<Instant> rateLimitedUntil = new AtomicReference<>();

    public WarcraftLogsClient(
            WarcraftLogsProperties properties,
            WarcraftLogsAuthService authService,
            JsonMapper jsonMapper
    ) {
        this.properties = properties;
        this.authService = authService;
        this.jsonMapper = jsonMapper;
        this.restClient = RestClient.builder().build();
    }

    public JsonNode query(String query, Map<String, Object> variables) {
        throwIfRateLimited();
        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(properties.apiUrl())
                    .headers(headers -> headers.setBearerAuth(authService.accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", query, "variables", variables))
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.TooManyRequests rateLimited) {
            Duration retryAfter = retryAfter(rateLimited);
            blockRequests(retryAfter);
            throw new RateLimitExceededException(retryAfter, rateLimited);
        }

        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("Warcraft Logs returned an empty response.");
        }
        JsonNode response = parseJson(responseBody);
        JsonNode errors = response.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new IllegalStateException("Warcraft Logs GraphQL error: "
                    + errors.path(0).path("message").asText("unknown error"));
        }
        return response.path("data");
    }

    static Duration retryAfter(HttpClientErrorException.TooManyRequests rateLimited) {
        HttpHeaders headers = rateLimited.getResponseHeaders();
        return parseRetryAfter(headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER));
    }

    private void throwIfRateLimited() {
        Instant now = Instant.now();
        Instant blockedUntil = rateLimitedUntil.get();
        if (blockedUntil != null && now.isBefore(blockedUntil)) {
            throw new RateLimitExceededException(Duration.between(now, blockedUntil), null);
        }
    }

    private void blockRequests(Duration retryAfter) {
        Instant candidate = Instant.now().plus(retryAfter);
        rateLimitedUntil.accumulateAndGet(candidate, WarcraftLogsClient::laterInstant);
    }

    private static Instant laterInstant(Instant current, Instant candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    static Duration parseRetryAfter(String retryAfter) {
        if (retryAfter == null) {
            return DEFAULT_RATE_LIMIT_BACKOFF;
        }
        try {
            long seconds = Long.parseLong(retryAfter);
            if (seconds <= 0) {
                return DEFAULT_RATE_LIMIT_BACKOFF;
            }
            return Duration.ofSeconds(Math.min(seconds, MAX_RATE_LIMIT_BACKOFF_SECONDS));
        } catch (NumberFormatException ignored) {
            return DEFAULT_RATE_LIMIT_BACKOFF;
        }
    }

    public RateLimit rateLimit() {
        JsonNode data = query(RATE_LIMIT_QUERY, Map.of()).path("rateLimitData");
        return new RateLimit(
                data.path("limitPerHour").asInt(0),
                data.path("pointsSpentThisHour").asDouble(0),
                data.path("pointsResetIn").asInt(0)
        );
    }

    private JsonNode parseJson(String responseBody) {
        try {
            return jsonMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new IllegalStateException("Warcraft Logs returned invalid JSON.", e);
        }
    }

    public record RateLimit(int limitPerHour, double pointsSpentThisHour, int pointsResetIn) {
        public double usedPercentage() {
            return limitPerHour <= 0 ? 0 : pointsSpentThisHour * 100.0 / limitPerHour;
        }
    }

    public static final class RateLimitExceededException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;
        private final Duration retryAfter;

        RateLimitExceededException(Duration retryAfter, Throwable cause) {
            super("Warcraft Logs request limit was reached.", cause);
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }
}
