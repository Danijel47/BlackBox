package com.blackbox.wow.client;

import com.blackbox.wow.client.MPlusObservation.Member;
import com.blackbox.wow.client.MPlusObservation.Modifier;
import com.blackbox.wow.client.MPlusObservation.RunDetails;
import com.blackbox.wow.client.MPlusObservation.RunSummary;
import com.blackbox.wow.client.RaiderIoCollectionException.Category;
import com.blackbox.wow.properties.MPlusCollectionProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RaiderIoClient {

    private static final Pattern SEASON_SLUG_PATTERN = Pattern.compile("/(season-[^/]+)/");

    private final RestClient rio;
    private final RestClient mplusTitle;
    private final JsonMapper json;
    private final int collectionRetryMaxDelaySeconds;

    public RaiderIoClient(
            @Qualifier("raiderIoRestClient") RestClient rio,
            @Qualifier("mplusTitleRestClient") RestClient mplusTitle,
            JsonMapper json,
            MPlusCollectionProperties collectionProperties
    ) {
        this.rio = rio;
        this.mplusTitle = mplusTitle;
        this.json = json;
        this.collectionRetryMaxDelaySeconds = collectionProperties.retryMaxDelaySeconds();
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
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse Raider.IO affixes JSON", e);
        }
    }

    public JsonNode getGuildProfile(String region, String realm, String guildName) {

        String body = rio.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/guilds/profile")
                        .queryParam("region", region)
                        .queryParam("realm", realm)
                        .queryParam("name", guildName)
                        .queryParam("fields", "name,profile_url,raid_progression")
                        .build())
                .retrieve()
                .body(String.class);

        try {
            return json.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse Raider.IO guild JSON", e);
        }
    }

    public RaiderIoScore getCurrentMPlusScore(String region, String realm, String name) {
        String body = rio.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/characters/profile")
                        .queryParam("region", region)
                        .queryParam("realm", realm)
                        .queryParam("name", name)
                        .queryParam("fields", "mythic_plus_scores_by_season:current")
                        .build())
                .retrieve()
                .body(String.class);

        if (body == null) {
            throw new IllegalStateException("Empty response from Raider.IO");
        }
        JsonNode profile;
        try {
            profile = json.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse Raider.IO score JSON", e);
        }
        JsonNode seasons = profile.path("mythic_plus_scores_by_season");
        if (!seasons.isArray() || seasons.isEmpty()) {
            throw new IllegalStateException("No mythic_plus_scores_by_season returned");
        }
        JsonNode scores = seasons.get(0).path("scores");

        Instant updatedAt = Instant.now();
        return new RaiderIoScore(
                profile.path("name").asText(name),
                profile.path("realm").asText(realm),
                profile.path("region").asText(region),
                decimalOrNull(scores, "all"),
                decimalOrNull(scores, "dps"),
                decimalOrNull(scores, "healer"),
                decimalOrNull(scores, "tank"),
                profile.path("profile_url").asText(""),
                updatedAt
        );
    }

    public MPlusObservation getMPlusObservation(String region, String realm, String name) {
        String body = collectionRequest(() -> rio.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/characters/profile")
                        .queryParam("region", region)
                        .queryParam("realm", realm)
                        .queryParam("name", name)
                        .queryParam("fields", String.join(",",
                                "mythic_plus_scores_by_season:current",
                                "mythic_plus_recent_runs",
                                "mythic_plus_best_runs:all",
                                "mythic_plus_weekly_highest_level_runs",
                                "mythic_plus_previous_weekly_highest_level_runs"
                        ))
                        .build())
                .retrieve()
                .body(String.class));
        try {
            return parseMPlusObservation(json.readTree(body), region, realm, name);
        } catch (RaiderIoCollectionException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new RaiderIoCollectionException(Category.INVALID_RESPONSE,
                    "Raider.IO returned an invalid character response", e);
        }
    }

    public RunDetails getMPlusRunDetails(String season, long runId) {
        String body = collectionRequest(() -> rio.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/mythic-plus/run-details")
                        .queryParam("season", season)
                        .queryParam("id", runId)
                        .build())
                .retrieve()
                .body(String.class));
        try {
            return parseRunDetails(json.readTree(body));
        } catch (JsonProcessingException e) {
            throw new RaiderIoCollectionException(Category.INVALID_RESPONSE,
                    "Raider.IO returned invalid run details", e);
        }
    }

    static MPlusObservation parseMPlusObservation(
            JsonNode profile,
            String requestedRegion,
            String requestedRealm,
            String requestedName
    ) {
        JsonNode seasons = profile.path("mythic_plus_scores_by_season");
        if (!seasons.isArray() || seasons.isEmpty()) {
            throw new RaiderIoCollectionException(Category.INVALID_RESPONSE,
                    "Raider.IO returned no current Mythic+ season");
        }
        JsonNode currentSeason = seasons.get(0);
        String season = currentSeason.path("season").asText("");
        if (season.isBlank()) {
            throw new RaiderIoCollectionException(Category.INVALID_RESPONSE,
                    "Raider.IO returned no current Mythic+ season key");
        }
        LinkedHashMap<Long, RunSummary> runs = new LinkedHashMap<>();
        mergeRuns(runs, profile.path("mythic_plus_recent_runs"), RunSource.RECENT);
        mergeRuns(runs, profile.path("mythic_plus_best_runs"), RunSource.BEST);
        JsonNode weeklyNodes = profile.path("mythic_plus_weekly_highest_level_runs");
        JsonNode previousWeeklyNodes = profile.path("mythic_plus_previous_weekly_highest_level_runs");
        List<RunSummary> weeklyRuns = parseRuns(weeklyNodes, RunSource.WEEKLY);
        List<RunSummary> previousWeeklyRuns = parseRuns(previousWeeklyNodes, RunSource.WEEKLY);
        mergeRunList(runs, weeklyRuns);
        mergeRunList(runs, previousWeeklyRuns);
        JsonNode scores = currentSeason.path("scores");
        return new MPlusObservation(
                profile.path("name").asText(requestedName),
                profile.path("realm").asText(requestedRealm),
                profile.path("region").asText(requestedRegion),
                season,
                decimalOrNull(scores, "all"),
                decimalOrNull(scores, "dps"),
                decimalOrNull(scores, "healer"),
                decimalOrNull(scores, "tank"),
                parseInstant(profile.path("last_crawled_at").asText("")),
                List.copyOf(runs.values()),
                weeklyRuns,
                previousWeeklyRuns,
                previousWeeklyNodes.isArray()
        );
    }

    public MPlusStaticData getMPlusStaticData(int expansionId) {
        String body = collectionRequest(() -> rio.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/mythic-plus/static-data")
                        .queryParam("expansion_id", expansionId)
                        .build())
                .retrieve()
                .body(String.class));
        try {
            return parseMPlusStaticData(json.readTree(body));
        } catch (JsonProcessingException e) {
            throw new RaiderIoCollectionException(Category.INVALID_RESPONSE,
                    "Raider.IO returned invalid Mythic+ static data", e);
        }
    }

    static MPlusStaticData parseMPlusStaticData(JsonNode response) {
        JsonNode seasonNodes = response.path("seasons");
        if (!seasonNodes.isArray()) {
            throw new RaiderIoCollectionException(Category.INVALID_RESPONSE,
                    "Raider.IO returned no Mythic+ seasons");
        }
        List<MPlusStaticData.Season> seasons = new ArrayList<>();
        for (JsonNode seasonNode : seasonNodes) {
            String seasonKey = seasonNode.path("slug").asText("");
            if (seasonKey.isBlank()) {
                continue;
            }
            List<MPlusStaticData.Dungeon> dungeons = parseStaticDungeons(seasonNode.path("dungeons"));
            seasons.add(new MPlusStaticData.Season(
                    seasonKey,
                    seasonNode.path("name").asText(seasonKey),
                    seasonNode.path("short_name").asText(seasonKey),
                    dungeons
            ));
        }
        if (seasons.isEmpty()) {
            throw new RaiderIoCollectionException(Category.INVALID_RESPONSE,
                    "Raider.IO returned no valid Mythic+ seasons");
        }
        return new MPlusStaticData(List.copyOf(seasons));
    }

    private static List<MPlusStaticData.Dungeon> parseStaticDungeons(JsonNode dungeonNodes) {
        if (!dungeonNodes.isArray()) {
            return List.of();
        }
        List<MPlusStaticData.Dungeon> dungeons = new ArrayList<>();
        for (JsonNode dungeon : dungeonNodes) {
            int id = dungeon.path("id").asInt(0);
            int challengeModeId = dungeon.path("challenge_mode_id").asInt(0);
            int timer = dungeon.path("keystone_timer_seconds").asInt(0);
            if (id <= 0 || challengeModeId <= 0 || timer <= 0) {
                continue;
            }
            dungeons.add(new MPlusStaticData.Dungeon(
                    id,
                    challengeModeId,
                    dungeon.path("slug").asText(""),
                    dungeon.path("name").asText("Unknown dungeon"),
                    dungeon.path("short_name").asText("Unknown"),
                    timer
            ));
        }
        return List.copyOf(dungeons);
    }

    static RunDetails parseRunDetails(JsonNode details) {
        List<Member> members = new ArrayList<>();
        JsonNode roster = details.path("roster");
        if (roster.isArray()) {
            for (JsonNode rosterMember : roster) {
                JsonNode character = rosterMember.path("character");
                String characterName = character.path("name").asText("");
                if (characterName.isBlank()) {
                    continue;
                }
                members.add(new Member(
                        firstText(character.path("region"), "short_name", "slug", "name"),
                        firstText(character.path("realm"), "name", "slug"),
                        characterName,
                        character.path("class").path("name").asText(""),
                        character.path("spec").path("name").asText(""),
                        rosterMember.path("role").asText(character.path("spec").path("role").asText(""))
                ));
            }
        }
        List<Modifier> modifiers = new ArrayList<>();
        JsonNode modifierNodes = details.path("weekly_modifiers");
        if (modifierNodes.isArray()) {
            for (JsonNode modifier : modifierNodes) {
                int id = modifier.path("id").asInt(0);
                if (id > 0) {
                    modifiers.add(new Modifier(
                            id,
                            modifier.path("name").asText("Unknown"),
                            modifier.path("slug").asText("")
                    ));
                }
            }
        }
        return new RunDetails(List.copyOf(members), List.copyOf(modifiers));
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
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse Raider.IO vault JSON", e);
        }

        String resolvedName = profile.path("name").asText(name);
        String resolvedRealm = profile.path("realm").asText(realm);
        String profileUrl = profile.path("profile_url").asText("");

        List<MPlusRun> runs = new ArrayList<>();
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

    public List<RaidRanking> getMythicRaidRankings(String raidSlug, int limit) {
        String body = rio.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/raiding/raid-rankings")
                        .queryParam("raid", raidSlug)
                        .queryParam("difficulty", "mythic")
                        .queryParam("region", "world")
                        .queryParam("limit", Math.max(1, Math.min(limit, 200)))
                        .queryParam("page", 0)
                        .build())
                .retrieve()
                .body(String.class);

        try {
            return parseRaidRankings(json.readTree(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse Raider.IO raid rankings JSON", e);
        }
    }

    static List<RaidRanking> parseRaidRankings(JsonNode response) {
        JsonNode rankingNodes = response.path("raidRankings");
        if (!rankingNodes.isArray()) {
            return List.of();
        }

        List<RaidRanking> rankings = new ArrayList<>();
        for (JsonNode rankingNode : rankingNodes) {
            JsonNode guild = rankingNode.path("guild");
            String guildName = firstText(guild, "displayName", "name");
            if (guildName.isBlank()) {
                continue;
            }
            rankings.add(new RaidRanking(
                    Math.max(0, rankingNode.path("rank").asInt(0)),
                    guildName,
                    guild.path("realm").path("name").asText("Unknown realm"),
                    guild.path("region").path("short_name").asText("World"),
                    parseBossDefeats(rankingNode.path("encountersDefeated")),
                    parseBossProgress(rankingNode.path("encountersPulled")),
                    guild.path("path").asText("")
            ));
        }
        return List.copyOf(rankings);
    }

    private static List<RaidBossDefeat> parseBossDefeats(JsonNode defeatNodes) {
        if (!defeatNodes.isArray()) {
            return List.of();
        }
        List<RaidBossDefeat> defeats = new ArrayList<>();
        for (JsonNode defeatNode : defeatNodes) {
            String slug = defeatNode.path("slug").asText("");
            Instant firstDefeatedAt = parseInstant(defeatNode.path("firstDefeated").asText(""));
            if (!slug.isBlank() && firstDefeatedAt != null) {
                defeats.add(new RaidBossDefeat(slug, firstDefeatedAt));
            }
        }
        return List.copyOf(defeats);
    }

    private static List<RaidBossProgress> parseBossProgress(JsonNode progressNodes) {
        if (!progressNodes.isArray()) {
            return List.of();
        }
        List<RaidBossProgress> progress = new ArrayList<>();
        for (JsonNode progressNode : progressNodes) {
            String slug = progressNode.path("slug").asText("");
            JsonNode bestPercentNode = progressNode.path("bestPercent");
            int pullCount = Math.max(0, progressNode.path("numPulls").asInt(0));
            if (slug.isBlank() || !bestPercentNode.isNumber() || pullCount == 0) {
                continue;
            }
            BigDecimal bestPercent = bestPercentNode.decimalValue();
            if (bestPercent.compareTo(BigDecimal.ZERO) < 0
                    || bestPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
                continue;
            }
            progress.add(new RaidBossProgress(
                    slug,
                    progressNode.path("isDefeated").asBoolean(false),
                    pullCount,
                    bestPercent
            ));
        }
        return List.copyOf(progress);
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
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
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse Raider.IO season recap JSON", e);
        }

        String resolvedName = profile.path("name").asText(name);
        String resolvedRealm = profile.path("realm").asText(realm);
        String profileUrl = profile.path("profile_url").asText("");
        List<DungeonRunCount> dungeons = new ArrayList<>();

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
        } catch (JsonProcessingException e) {
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
        } catch (JsonProcessingException e) {
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

    private String collectionRequest(Supplier<String> request) {
        try {
            return requireCollectionBody(request.get());
        } catch (RuntimeException firstFailure) {
            RaiderIoCollectionException translated = translateCollectionFailure(firstFailure);
            if (!isRetryable(translated.category())) {
                throw translated;
            }
            pauseBeforeRetry(firstFailure);
            try {
                return requireCollectionBody(request.get());
            } catch (RuntimeException retryFailure) {
                throw translateCollectionFailure(retryFailure);
            }
        }
    }

    private static String requireCollectionBody(String body) {
        if (body == null || body.isBlank()) {
            throw new RaiderIoCollectionException(Category.INVALID_RESPONSE,
                    "Raider.IO returned an empty response");
        }
        return body;
    }

    private static RaiderIoCollectionException translateCollectionFailure(RuntimeException failure) {
        if (failure instanceof RaiderIoCollectionException collectionException) {
            return collectionException;
        }
        if (failure instanceof HttpClientErrorException.NotFound) {
            return new RaiderIoCollectionException(Category.NOT_FOUND,
                    "Raider.IO could not find the requested resource", failure);
        }
        if (failure instanceof HttpClientErrorException.TooManyRequests) {
            return new RaiderIoCollectionException(Category.RATE_LIMITED,
                    "Raider.IO rate limit was reached", failure);
        }
        if (failure instanceof HttpServerErrorException) {
            return new RaiderIoCollectionException(Category.UPSTREAM,
                    "Raider.IO is temporarily unavailable", failure);
        }
        if (failure instanceof ResourceAccessException) {
            return new RaiderIoCollectionException(Category.TIMEOUT,
                    "Raider.IO request timed out", failure);
        }
        return new RaiderIoCollectionException(Category.UNKNOWN,
                "Raider.IO collection failed", failure);
    }

    private static boolean isRetryable(Category category) {
        return category == Category.RATE_LIMITED
                || category == Category.TIMEOUT
                || category == Category.UPSTREAM;
    }

    private void pauseBeforeRetry(RuntimeException failure) {
        long retryDelaySeconds = 1;
        if (failure instanceof HttpClientErrorException.TooManyRequests rateLimited) {
            String retryAfter = rateLimited.getResponseHeaders().getFirst("Retry-After");
            retryDelaySeconds = parseRetryAfter(retryAfter);
        }
        retryDelaySeconds = Math.min(retryDelaySeconds, collectionRetryMaxDelaySeconds);
        if (retryDelaySeconds <= 0) {
            return;
        }
        try {
            Thread.sleep(retryDelaySeconds * 1_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RaiderIoCollectionException(Category.UNKNOWN,
                    "Raider.IO retry was interrupted", interrupted);
        }
    }

    private static long parseRetryAfter(String retryAfter) {
        if (retryAfter == null) {
            return 1;
        }
        try {
            return Math.max(0, Long.parseLong(retryAfter));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static void mergeRuns(
            LinkedHashMap<Long, RunSummary> runs,
            JsonNode runNodes,
            RunSource source
    ) {
        if (!runNodes.isArray()) {
            return;
        }
        for (JsonNode runNode : runNodes) {
            RunSummary parsed = parseRun(runNode, source);
            if (parsed != null) {
                runs.merge(parsed.raiderIoRunId(), parsed, RaiderIoClient::mergeRunSources);
            }
        }
    }

    private static List<RunSummary> parseRuns(JsonNode runNodes, RunSource source) {
        LinkedHashMap<Long, RunSummary> runs = new LinkedHashMap<>();
        mergeRuns(runs, runNodes, source);
        return List.copyOf(runs.values());
    }

    private static void mergeRunList(LinkedHashMap<Long, RunSummary> target, List<RunSummary> runs) {
        for (RunSummary run : runs) {
            target.merge(run.raiderIoRunId(), run, RaiderIoClient::mergeRunSources);
        }
    }

    private static RunSummary parseRun(JsonNode run, RunSource source) {
        long runId = run.path("keystone_run_id").asLong(0);
        int level = run.path("mythic_level").asInt(0);
        Instant completedAt = parseInstant(run.path("completed_at").asText(""));
        if (runId <= 0 || level <= 0 || completedAt == null) {
            return null;
        }
        long clearTime = Math.max(0, run.path("clear_time_ms").asLong(0));
        long parTime = Math.max(0, run.path("par_time_ms").asLong(0));
        return new RunSummary(
                runId,
                firstText(run, "dungeon", "name", "short_name"),
                firstText(run, "short_name", "dungeon", "name"),
                nullablePositiveInteger(run, "map_challenge_mode_id"),
                level,
                completedAt,
                clearTime,
                parTime,
                Math.max(0, run.path("num_keystone_upgrades").asInt(0)),
                decimalOrNull(run, "score"),
                source == RunSource.RECENT,
                source == RunSource.BEST,
                source == RunSource.WEEKLY
        );
    }

    private static RunSummary mergeRunSources(RunSummary left, RunSummary right) {
        return new RunSummary(
                left.raiderIoRunId(),
                left.dungeonName(),
                left.dungeonShortName(),
                left.mapChallengeModeId(),
                left.mythicLevel(),
                left.completedAt(),
                left.clearTimeMs(),
                left.parTimeMs(),
                left.keystoneUpgrades(),
                left.score(),
                left.recent() || right.recent(),
                left.best() || right.best(),
                left.weekly() || right.weekly()
        );
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }

    private static Integer nullablePositiveInteger(JsonNode node, String field) {
        int value = node.path(field).asInt(0);
        return value > 0 ? value : null;
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

    private enum RunSource {
        RECENT,
        BEST,
        WEEKLY
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

    public record RaidRanking(
            int rank,
            String guildName,
            String realm,
            String region,
            List<RaidBossDefeat> defeatedBosses,
            List<RaidBossProgress> bossProgress,
            String guildPath
    ) {
    }

    public record RaidBossDefeat(String slug, Instant firstDefeatedAt) {
    }

    public record RaidBossProgress(String slug, boolean defeated, int pullCount, BigDecimal bestPercent) {
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
