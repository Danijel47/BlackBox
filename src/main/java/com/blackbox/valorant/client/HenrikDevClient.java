package com.blackbox.valorant.client;

import com.blackbox.valorant.properties.ValorantProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

@Component
public class HenrikDevClient {
    private static final int MATCH_LIMIT = 20;
    private static final int PAGE_SIZE = 10;
    private final RestClient http;
    private final JsonMapper json;
    private final ValorantProperties properties;
    private final Cache<HistoryKey, Headshots> cache = Caffeine.newBuilder()
            .maximumSize(250).expireAfterWrite(Duration.ofMinutes(5)).build();
    private volatile Instant retryAfter = Instant.EPOCH;

    public HenrikDevClient(@Qualifier("henrikDevRestClient") RestClient http,
                           JsonMapper json, ValorantProperties properties) {
        this.http = http;
        this.json = json;
        this.properties = properties;
    }

    public Account account(String name, String tag) {
        JsonNode data = request(builder -> builder.path("/valorant/v2/account/{name}/{tag}")
                .build(name, tag));
        List<String> platforms = new ArrayList<>();
        data.path("platforms").forEach(platform -> platforms.add(platform.asText().toLowerCase(Locale.ROOT)));
        return new Account(text(data, "puuid"), text(data, "name"), text(data, "tag"),
                text(data, "region"), List.copyOf(platforms));
    }

    public Headshots headshots(String region, String platform, String puuid) {
        return cache.get(new HistoryKey(region, platform, puuid), this::loadHeadshots);
    }

    private Headshots loadHeadshots(HistoryKey key) {
        var matches = new LinkedHashMap<String, MatchHits>();
        int start = 0;
        // Bound requests even if a provider ignores pagination or returns unexpected queues.
        for (int page = 0; page < 5 && matches.size() < MATCH_LIMIT; page++) {
            int offset = start;
            JsonNode data = request(builder -> builder
                    .path("/valorant/v4/by-puuid/matches/{region}/{platform}/{puuid}")
                    .queryParam("mode", "competitive").queryParam("size", PAGE_SIZE)
                    .queryParam("start", offset).build(key.region(), key.platform(), key.puuid()));
            if (!data.isArray()) {
                throw new IllegalStateException("HenrikDev returned invalid match history.");
            }
            int previousSize = matches.size();
            for (JsonNode match : data) {
                JsonNode metadata = match.path("metadata");
                String queue = text(metadata.path("queue"), "id");
                if (!metadata.path("is_completed").isBoolean()) {
                    throw new IllegalStateException("HenrikDev returned invalid match completion data.");
                }
                if (!"competitive".equalsIgnoreCase(queue) || !metadata.path("is_completed").asBoolean()) {
                    continue;
                }
                String id = text(metadata, "match_id");
                if (!matches.containsKey(id)) {
                    matches.put(id, parseMatch(match, key.puuid(), id));
                }
            }
            if (data.size() < PAGE_SIZE || matches.size() == previousSize) {
                break;
            }
            start += data.size();
        }
        List<MatchHits> latest = matches.values().stream()
                .sorted(Comparator.comparing(MatchHits::startedAt).reversed())
                .limit(MATCH_LIMIT).toList();
        return new Headshots(latest.size(), latest.stream().mapToLong(MatchHits::head).sum(),
                latest.stream().mapToLong(MatchHits::body).sum(), latest.stream().mapToLong(MatchHits::leg).sum());
    }

    private static MatchHits parseMatch(JsonNode match, String puuid, String id) {
        JsonNode player = null;
        for (JsonNode candidate : match.path("players")) {
            if (puuid.equals(candidate.path("puuid").asText())) {
                player = candidate;
                break;
            }
        }
        if (player == null) {
            throw new IllegalStateException("Tracked player is missing from match statistics.");
        }
        Instant startedAt;
        try {
            startedAt = Instant.parse(text(match.path("metadata"), "started_at"));
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("HenrikDev returned an invalid match timestamp.");
        }
        JsonNode stats = player.path("stats");
        return new MatchHits(id, startedAt, hitCount(stats, "headshots"),
                hitCount(stats, "bodyshots"), hitCount(stats, "legshots"));
    }

    private static long hitCount(JsonNode stats, String field) {
        JsonNode value = stats.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asLong() < 0) {
            throw new IllegalStateException("HenrikDev returned missing or invalid hit counts.");
        }
        return value.asLong();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("HenrikDev returned missing account or match fields.");
        }
        return value.asText();
    }

    private synchronized JsonNode request(Function<UriBuilder, java.net.URI> uri) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new UnavailableException("Set HENRIKDEV_API_KEY to enable Valorant reports.");
        }
        if (retryAfter.isAfter(Instant.now())) {
            throw new UnavailableException("HenrikDev rate limit reached; try again later.");
        }
        try {
            var result = http.get().uri(uri).header("Authorization", properties.apiKey())
                    .retrieve().toEntity(String.class);
            trackBudget(result.getHeaders());
            String body = result.getBody();
            if (body == null || body.isBlank()) {
                throw new IllegalStateException("HenrikDev returned an empty response.");
            }
            JsonNode response = json.readTree(body);
            if (response.path("status").asInt() != 200 || !response.hasNonNull("data")) {
                throw new IllegalStateException("HenrikDev returned an unsuccessful response.");
            }
            return response.get("data");
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429) {
                HttpHeaders headers = e.getResponseHeaders();
                long delay = headers == null ? 60 : seconds(headers.getFirst("Retry-After"),
                        seconds(headers.getFirst("X-RateLimit-Reset"), 60));
                retryAfter = Instant.now().plusSeconds(Math.max(1, delay) + 1);
                throw new UnavailableException("HenrikDev rate limit reached; try again later.");
            }
            if (status == 401) {
                throw new UnavailableException("HenrikDev rejected the API key (HTTP 401). Check HENRIKDEV_API_KEY.");
            }
            if (status == 403) {
                throw new UnavailableException("HenrikDev denied access (HTTP 403). Check your API key's permissions or approval.");
            }
            if (status == 404) {
                throw new UnavailableException("Riot account or match history was not found.");
            }
            throw new UnavailableException("HenrikDev is temporarily unavailable.");
        } catch (RestClientException e) {
            throw new UnavailableException("HenrikDev could not be reached; try again later.");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("HenrikDev returned invalid JSON.");
        }
    }

    private void trackBudget(HttpHeaders headers) {
        String remaining = headers.getFirst("X-RateLimit-Remaining");
        if (remaining != null && remaining.matches("[0-9]{1,9}")
                && Long.parseLong(remaining) < PAGE_SIZE + 2L) {
            // A cold page may cost one API call, one history call, and ten match calls.
            retryAfter = Instant.now().plusSeconds(Math.max(1,
                    seconds(headers.getFirst("X-RateLimit-Reset"), 60)) + 1);
        }
    }

    private static long seconds(String value, long fallback) {
        return value != null && value.matches("[0-9]{1,5}") ? Long.parseLong(value) : fallback;
    }

    public record Account(String puuid, String name, String tag, String region, List<String> platforms) {
        public String riotId() { return name + "#" + tag; }
    }

    public record Headshots(int matches, long headHits, long bodyHits, long legHits) {
        public long totalHits() { return headHits + bodyHits + legHits; }
        public Optional<BigDecimal> percentage() {
            return totalHits() == 0 ? Optional.empty() : Optional.of(BigDecimal.valueOf(headHits)
                    .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(totalHits()), 1, RoundingMode.HALF_UP));
        }
    }

    public static class UnavailableException extends IllegalStateException {
        public UnavailableException(String message) { super(message); }
    }

    private record HistoryKey(String region, String platform, String puuid) { }
    private record MatchHits(String id, Instant startedAt, long head, long body, long leg) { }
}
