package com.blackbox.wow.client;

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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class RaiderIoClient {

    private static final Pattern SEASON_SLUG_PATTERN = Pattern.compile("/(season-[^/]+)/");

    private final RestClient rio;
    private final RestClient mplusTitle;
    private final JsonMapper json;

    public RaiderIoClient(
            @Qualifier("raiderIoRestClient") RestClient rio,
            @Qualifier("mplusTitleRestClient") RestClient mplusTitle,
            JsonMapper json
    ) {
        this.rio = rio;
        this.mplusTitle = mplusTitle;
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

                    log.info("Raider.IO URL = {}", uri);   // this prints the full URL
                    return uri;
                })
                .retrieve()
                .body(String.class);

        log.debug("Raider.IO RAW JSON (first 800 chars) = {}",
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

    public WeeklyVaultProgress getWeeklyVaultProgress(String region, String realm, String name) {
        String body = rio.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/characters/profile")
                        .queryParam("region", region)
                        .queryParam("realm", realm)
                        .queryParam("name", name)
                        .queryParam("fields", "mythic_plus_weekly_highest_level_runs")
                        .build())
                .retrieve()
                .body(String.class);

        JsonNode profile;
        try {
            profile = json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Raider.IO vault JSON", e);
        }

        String resolvedName = profile.path("name").asText(name);
        String resolvedRealm = profile.path("realm").asText(realm);
        String profileUrl = profile.path("profile_url").asText("");

        List<MPlusRun> runs = new java.util.ArrayList<>();
        JsonNode weeklyRuns = profile.path("mythic_plus_weekly_highest_level_runs");
        if (weeklyRuns.isArray()) {
            for (JsonNode run : weeklyRuns) {
                int level = run.path("mythic_level").asInt(0);
                if (level <= 0) continue;

                String dungeon = firstText(run, "short_name", "dungeon", "zone", "name");
                if (dungeon.isBlank()) {
                    dungeon = "Unknown dungeon";
                }

                String completedAt = run.path("completed_at").asText("");
                runs.add(new MPlusRun(level, dungeon, completedAt));
            }
        }

        runs.sort((left, right) -> Integer.compare(right.level(), left.level()));
        return new WeeklyVaultProgress(resolvedName, resolvedRealm, region, runs, profileUrl);
    }

    public MPlusSeasonRunCounts getMPlusSeasonRunCounts(
            String region,
            String realm,
            String name,
            String season
    ) {
        String body = rio.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/characters/profile")
                        .queryParam("region", region)
                        .queryParam("realm", realm)
                        .queryParam("name", name)
                        .queryParam("fields", "mythic_plus_dungeon_run_counts:" + season)
                        .build())
                .retrieve()
                .body(String.class);

        JsonNode profile;
        try {
            profile = json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Raider.IO season recap JSON", e);
        }

        String resolvedName = profile.path("name").asText(name);
        String resolvedRealm = profile.path("realm").asText(realm);
        String profileUrl = profile.path("profile_url").asText("");
        List<DungeonRunCount> dungeons = new java.util.ArrayList<>();

        JsonNode runCounts = profile.path("mythic_plus_dungeon_run_counts");
        if (runCounts.isArray()) {
            for (JsonNode runCount : runCounts) {
                String dungeon = runCount.path("dungeon").asText("Unknown dungeon");
                String shortName = runCount.path("short_name").asText(dungeon);
                int total = Math.max(0, runCount.path("season_runs_total").asInt(0));
                int timed = Math.max(0, runCount.path("season_runs_timed").asInt(0));
                dungeons.add(new DungeonRunCount(dungeon, shortName, total, Math.min(timed, total)));
            }
        }

        return new MPlusSeasonRunCounts(
                resolvedName,
                resolvedRealm,
                region,
                season,
                List.copyOf(dungeons),
                profileUrl
        );
    }

    public MPlusTitleCutoff getCurrentMPlusTitleCutoff(String region) {
        return getCurrentMPlusTitleCutoff(region, "p990");
    }

    public MPlusTitleCutoff getCurrentMPlusTitleCutoff(String region, String percentileKey) {
        JsonNode affixes = getWeeklyAffixes(region, "en");
        String season = currentSeasonFromAffixes(affixes);

        String body = rio.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/mythic-plus/season-cutoffs")
                        .queryParam("region", region)
                        .queryParam("season", season)
                        .build())
                .retrieve()
                .body(String.class);

        JsonNode cutoffs;
        try {
            cutoffs = json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Raider.IO season cutoffs JSON", e);
        }

        JsonNode cutoffAll = cutoffs.path("cutoffs").path(percentileKey).path("all");
        if (cutoffAll.isMissingNode() || cutoffAll.path("quantileMinValue").isMissingNode()) {
            throw new IllegalStateException("Raider.IO season cutoffs did not include " + percentileKey + " all score");
        }

        BigDecimal score = cutoffAll.path("quantileMinValue").decimalValue();
        int population = cutoffAll.path("quantilePopulationCount").asInt(0);
        String updatedAt = cutoffs.path("cutoffs").path("updatedAt").asText("");
        return new MPlusTitleCutoff(region, season, score, population, updatedAt);
    }

    public MPlusTitlePrediction getCurrentMPlusTitlePrediction(String region, String percentileKey) {
        JsonNode affixes = getWeeklyAffixes(region, "en");
        String season = currentSeasonFromAffixes(affixes);
        String trackerSeason = toMPlusTitleSeasonSlug(season);
        String normalizedRegion = region.toUpperCase(Locale.ROOT);
        String seriesId = switch (percentileKey) {
            case "p990" -> "extrapolation-score100";
            case "p999" -> "extrapolation";
            default -> throw new IllegalArgumentException("Unsupported title prediction percentile: " + percentileKey);
        };

        String body = mplusTitle.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/{season}")
                        .build(trackerSeason))
                .retrieve()
                .body(String.class);

        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse M+ title prediction JSON", e);
        }

        JsonNode series = root.path("score").path("series").path(normalizedRegion);
        if (!series.isArray()) {
            throw new IllegalStateException("M+ title prediction did not include region " + normalizedRegion);
        }

        JsonNode extrapolation = null;
        for (JsonNode item : series) {
            if (seriesId.equals(item.path("id").asText(""))) {
                extrapolation = item;
                break;
            }
        }

        if (extrapolation == null) {
            throw new IllegalStateException("M+ title prediction did not include " + seriesId);
        }

        JsonNode data = extrapolation.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("M+ title prediction included no extrapolated points");
        }

        JsonNode lastPoint = data.get(data.size() - 1);
        if (!lastPoint.isArray() || lastPoint.size() < 2) {
            throw new IllegalStateException("M+ title prediction point was malformed");
        }

        Instant predictionFor = Instant.ofEpochMilli(lastPoint.get(0).asLong());
        BigDecimal predictedScore = lastPoint.get(1).decimalValue();
        return new MPlusTitlePrediction(normalizedRegion, trackerSeason, percentileKey, predictedScore, predictionFor);
    }

    private static String currentSeasonFromAffixes(JsonNode affixes) {
        String leaderboardUrl = affixes.path("leaderboard_url").asText("");
        Matcher matcher = SEASON_SLUG_PATTERN.matcher(leaderboardUrl);
        if (!matcher.find()) {
            throw new IllegalStateException("Could not determine current Mythic+ season from Raider.IO affixes");
        }
        return matcher.group(1);
    }

    private static String toMPlusTitleSeasonSlug(String raiderIoSeason) {
        Matcher matcher = Pattern.compile("^season-(.+)-(\\d+)$").matcher(raiderIoSeason);
        if (!matcher.matches()) {
            throw new IllegalStateException("Could not map Raider.IO season slug to title tracker slug: " + raiderIoSeason);
        }
        return matcher.group(1) + "-season-" + matcher.group(2);
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

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
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

    public record MPlusTitleCutoff(
            String region,
            String season,
            BigDecimal score,
            int population,
            String updatedAt
    ) {
    }

    public record MPlusTitlePrediction(
            String region,
            String season,
            String percentileKey,
            BigDecimal predictedScore,
            Instant predictionFor
    ) {
    }

    public record WeeklyVaultProgress(
            String name,
            String realm,
            String region,
            List<MPlusRun> runs,
            String profileUrl
    ) {
    }

    public record MPlusRun(int level, String dungeon, String completedAt) {
    }

    public record MPlusSeasonRunCounts(
            String name,
            String realm,
            String region,
            String season,
            List<DungeonRunCount> dungeons,
            String profileUrl
    ) {
    }

    public record DungeonRunCount(String dungeon, String shortName, int total, int timed) {
        public int depleted() {
            return total - timed;
        }
    }

}
