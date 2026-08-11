package com.example.blackbox.wow.warcraftlogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;

@Service
public class WarcraftLogsAuthService {

    private final WarcraftLogsProperties properties;
    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private AccessToken cachedToken;

    public WarcraftLogsAuthService(WarcraftLogsProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.restClient = RestClient.builder().build();
    }

    public synchronized String accessToken() {
        Instant now = Instant.now();
        if (cachedToken != null && cachedToken.expiresAt().isAfter(now.plusSeconds(30))) {
            return cachedToken.value();
        }
        if (isBlank(properties.clientId()) || isBlank(properties.clientSecret())) {
            throw new IllegalStateException("Warcraft Logs client ID and secret are not configured.");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        String responseBody = restClient.post()
                .uri(properties.tokenUrl())
                .headers(headers -> headers.setBasicAuth(properties.clientId(), properties.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);

        JsonNode response = parseJson(responseBody);

        String token = response == null ? "" : response.path("access_token").asText("");
        if (token.isBlank()) {
            throw new IllegalStateException("Warcraft Logs did not return an access token.");
        }
        long expiresIn = Math.max(60, response.path("expires_in").asLong(3600));
        cachedToken = new AccessToken(token, now.plusSeconds(expiresIn));
        return token;
    }

    private JsonNode parseJson(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("Warcraft Logs returned an empty authentication response.");
        }
        try {
            return jsonMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new IllegalStateException("Warcraft Logs returned invalid authentication JSON.", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record AccessToken(String value, Instant expiresAt) {
    }
}
