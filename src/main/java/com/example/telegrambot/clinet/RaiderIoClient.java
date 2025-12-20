package com.example.telegrambot.clinet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Slf4j
public class RaiderIoClient {

    private final RestClient rio;
    private final JsonMapper json;

    public RaiderIoClient(@Qualifier("raiderIoRestClient") RestClient rio, JsonMapper json) {
        this.rio = rio;
        this.json = json;
    }

    public JsonNode getWeeklyAffixes(String region, String locale) {
        String body = rio.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/mythic-plus/affixes")
                        .queryParam("region", region)
                        .queryParamIfPresent("locale", Optional.ofNullable(locale))
                        .build())
                .retrieve()
                .body(String.class);

        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Raider.IO affixes JSON", e);
        }
    }

    public JsonNode getGuildProfile(String region, String realm, String guildName) {

        String body = rio.get()
                .uri(uriBuilder -> {
                    var uri = uriBuilder
                            .path("/api/v1/guilds/profile")
                            .queryParam("region", region)
                            .queryParam("realm", realm)
                            .queryParam("name", guildName) // NOT encoded
                            .queryParam("fields", "name,profile_url,raid_progression")
                            .build();

                    log.info("Raider.IO URL = {}", uri);   // ✅ this prints the full URL
                    return uri;
                })
                .retrieve()
                .body(String.class);

        log.info("Raider.IO RAW JSON (first 800 chars) = {}",
                body == null ? "null" : body.substring(0, Math.min(800, body.length())));

        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse JSON: " + e.getMessage(), e);
        }
    }

    public RaiderIoScore getCurrentMPlusScore(String region, String realm, String name) {
        Map<String, Object> body = rio.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/characters/profile")
                        .queryParam("region", region)
                        .queryParam("realm", realm)
                        .queryParam("name", name)
                        .queryParam("fields", "mythic_plus_scores_by_season:current")
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        if (body == null) {
            throw new IllegalStateException("Empty response from Raider.IO");
        }

        String resolvedName = String.valueOf(body.getOrDefault("name", name));
        String profileUrl = String.valueOf(body.getOrDefault("profile_url", ""));

        Object seasonsObj = body.get("mythic_plus_scores_by_season");
        if (!(seasonsObj instanceof List<?> seasons) || seasons.isEmpty()) {
            throw new IllegalStateException("No mythic_plus_scores_by_season returned");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) seasons.getFirst();

        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) current.get("scores");

        BigDecimal all = toBigDecimal(scores, "all");
        BigDecimal dps = toBigDecimal(scores, "dps");
        BigDecimal healer = toBigDecimal(scores, "healer");
        BigDecimal tank = toBigDecimal(scores, "tank");

        Instant updatedAt = Instant.now();
        return new RaiderIoScore(resolvedName, realm, region, all, dps, healer, tank, profileUrl, updatedAt);
    }

    private static BigDecimal toBigDecimal(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        if (v == null) return null;
        try {
            return new BigDecimal(v.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public record RaiderIoScore(
            String name,
            String realm,
            String region,
            BigDecimal all,
            BigDecimal dps,
            BigDecimal healer,
            BigDecimal tank,
            String profileUrl,
            Instant fetchedAt
    ) {
    }

}
