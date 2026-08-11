package com.example.blackbox.wow.warcraftlogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class WarcraftLogsClient {

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
        String responseBody = restClient.post()
                .uri(properties.apiUrl())
                .headers(headers -> headers.setBearerAuth(authService.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query, "variables", variables))
                .retrieve()
                .body(String.class);

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
}
