package com.blackbox.wow.blizzard;

import com.blackbox.wow.properties.BlizzardApiProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Service
public class BlizzardAuthService {

    private final RestClient authClient;
    private final BlizzardApiProperties props;

    private volatile Token cached;

    public BlizzardAuthService(@Qualifier("blizzardAuthRestClient") RestClient authClient,
                               BlizzardApiProperties props) {
        this.authClient = authClient;
        this.props = props;
    }

    public String getAccessToken() {
        Token token = cached;
        if (token != null && !token.isExpired()) {
            return token.value();
        }

        if (props.clientId() == null || props.clientId().isBlank()
                || props.clientSecret() == null || props.clientSecret().isBlank()) {
            throw new IllegalStateException("Blizzard API client credentials are not configured (blizzard.client-id/client-secret)");
        }

        String basic = Base64.getEncoder().encodeToString(
                (props.clientId() + ":" + props.clientSecret()).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> body = authClient.post()
                .uri("/token")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials")
                .retrieve()
                .body(Map.class);

        if (body == null || body.get("access_token") == null) {
            throw new IllegalStateException("Blizzard auth response missing access_token");
        }

        String accessToken = body.get("access_token").toString();
        long expiresIn = parseLong(body.get("expires_in"), 3600L);
        Instant expiresAt = Instant.now().plus(Duration.ofSeconds(Math.max(60, expiresIn - 60)));

        cached = new Token(accessToken, expiresAt);
        return accessToken;
    }

    private static long parseLong(Object value, long fallback) {
        if (value == null) return fallback;
        try {
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            return fallback;
        }
    }

    private record Token(String value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
