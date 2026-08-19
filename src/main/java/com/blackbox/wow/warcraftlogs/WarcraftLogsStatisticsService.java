package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.entity.WarcraftLogPlayerRunEntity;
import com.blackbox.wow.entity.WarcraftLogProfileSnapshotEntity;
import com.blackbox.wow.repository.WarcraftLogPlayerRunRepository;
import com.blackbox.wow.repository.WarcraftLogProfileSnapshotRepository;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.blackbox.wow.service.MPlusRunCorrelationService;
import com.blackbox.wow.helper.MPlusRunMatcher.LogFight;
import com.blackbox.wow.helper.MPlusRunMatcher.PlayerIdentity;
import com.blackbox.wow.warcraftlogs.WarcraftLogsEventPager.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WarcraftLogsStatisticsService {

    private static final String CHARACTER_REPORTS_QUERY = """
            query CharacterReports($name: String!, $server: String!, $region: String!, $limit: Int!) {
              characterData {
                character(name: $name, serverSlug: $server, serverRegion: $region) {
                  name
                  recentReports(limit: $limit, page: 1) {
                    data {
                      code
                      revision
                      startTime
                      fights {
                        id
                        name
                        keystoneLevel
                        startTime
                        endTime
                        friendlyPlayers
                      }
                      masterData(translate: false) {
                        actors(type: "Player") {
                          id
                          name
                          server
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    private final WarcraftLogsClient client;
    private final WarcraftLogsProperties properties;
    private final TrackedPlayerService trackedPlayerService;
    private final WarcraftLogPlayerRunRepository runRepository;
    private final WarcraftLogProfileSnapshotRepository snapshotRepository;
    private final MPlusRunCorrelationService correlationService;
    private final WarcraftLogsEventPager eventPager;
    private final AtomicBoolean refreshRunning = new AtomicBoolean();

    public WarcraftLogsStatisticsService(
            WarcraftLogsClient client,
            WarcraftLogsProperties properties,
            TrackedPlayerService trackedPlayerService,
            WarcraftLogPlayerRunRepository runRepository,
            WarcraftLogProfileSnapshotRepository snapshotRepository,
            MPlusRunCorrelationService correlationService,
            WarcraftLogsEventPager eventPager
    ) {
        this.client = client;
        this.properties = properties;
        this.trackedPlayerService = trackedPlayerService;
        this.runRepository = runRepository;
        this.snapshotRepository = snapshotRepository;
        this.correlationService = correlationService;
        this.eventPager = eventPager;
    }

    public List<PlayerStatistics> statistics() {
        String seasonKey = properties.seasonKey();
        Map<Long, List<WarcraftLogPlayerRunEntity>> runsByProfile = runRepository.findBySeasonKey(seasonKey)
                .stream()
                .collect(Collectors.groupingBy(WarcraftLogPlayerRunEntity::getProfileId));
        Map<Long, WarcraftLogProfileSnapshotEntity> snapshotsByProfile = snapshotRepository
                .findBySeasonKey(seasonKey)
                .stream()
                .collect(Collectors.toMap(WarcraftLogProfileSnapshotEntity::getProfileId, value -> value));

        List<PlayerStatistics> result = new ArrayList<>();
        for (TrackedPlayer player : trackedPlayerService.activePlayers()) {
            List<WarcraftLogPlayerRunEntity> runs = runsByProfile.getOrDefault(player.profileId(), List.of());
            int interrupts = runs.stream().mapToInt(WarcraftLogPlayerRunEntity::getInterrupts).sum();
            int deaths = runs.stream().mapToInt(WarcraftLogPlayerRunEntity::getDeaths).sum();
            List<WarcraftLogPlayerRunEntity> rankedRuns = runs.stream()
                    .filter(WarcraftLogsStatisticsService::hasRankingMetrics)
                    .toList();
            List<BigDecimal> parses = nonNullMetrics(rankedRuns, WarcraftLogPlayerRunEntity::getParsePercentage);
            List<BigDecimal> keyParses = nonNullMetrics(
                    rankedRuns, WarcraftLogPlayerRunEntity::getKeyParsePercentage
            );
            List<BigDecimal> damagePerSecond = nonNullMetrics(
                    rankedRuns, WarcraftLogPlayerRunEntity::getDamagePerSecond
            );
            WarcraftLogProfileSnapshotEntity snapshot = snapshotsByProfile.get(player.profileId());
            result.add(new PlayerStatistics(
                    player.profileName(),
                    player.name(),
                    runs.size(),
                    rankedRuns.size(),
                    average(interrupts, runs.size()),
                    average(deaths, runs.size()),
                    average(parses),
                    average(keyParses),
                    average(damagePerSecond),
                    snapshot == null ? null : snapshot.getLastError()
            ));
        }
        return result;
    }

    public String seasonKey() {
        return properties.seasonKey();
    }

    public Instant seasonStart() {
        return properties.seasonStart();
    }

    public String combatMessage(String profileArgument) {
        String requestedProfile = profileArgument == null ? "" : profileArgument.trim();
        List<PlayerStatistics> selected = statistics().stream()
                .filter(statistic -> requestedProfile.isBlank()
                        || statistic.profileName().equalsIgnoreCase(requestedProfile))
                .toList();
        if (selected.isEmpty()) {
            return requestedProfile.isBlank()
                    ? "No Warcraft Logs combat statistics are available."
                    : "Active player profile not found: " + requestedProfile;
        }
        StringBuilder message = new StringBuilder("Warcraft Logs M+ combat — ")
                .append(properties.seasonKey()).append('\n');
        selected.forEach(statistic -> appendCombatStatistic(message, statistic));
        return message.append("Averages use logged runs only; missing/private logs are unavailable, not zero.")
                .toString();
    }

    private static void appendCombatStatistic(StringBuilder message, PlayerStatistics statistic) {
        message.append("• ").append(statistic.profileName()).append(" (")
                .append(statistic.characterName()).append(") — N=").append(statistic.dungeonRuns())
                .append(", interrupts ").append(formatMetric(statistic.averageInterrupts()))
                .append(", deaths ").append(formatMetric(statistic.averageDeaths()))
                .append(", Parse ").append(formatPercentMetric(statistic.averageParsePercentage()))
                .append(", Key ").append(formatPercentMetric(statistic.averageKeyParsePercentage()))
                .append(", DPS ").append(formatDamagePerSecond(statistic.averageDamagePerSecond()));
        if (statistic.rankedDungeonRuns() > 0) {
            message.append(" (ranked N=").append(statistic.rankedDungeonRuns()).append(')');
        }
        message.append('\n');
    }

    private static String formatMetric(BigDecimal value) {
        return value == null ? "unavailable" : value.stripTrailingZeros().toPlainString();
    }

    private static String formatPercentMetric(BigDecimal value) {
        return value == null ? "unavailable" : formatMetric(value) + '%';
    }

    private static String formatDamagePerSecond(BigDecimal value) {
        if (value == null) return "unavailable";
        DecimalFormat formatter = new DecimalFormat(
                "#,##0.##", DecimalFormatSymbols.getInstance(Locale.US)
        );
        return formatter.format(value);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshAfterStartup() {
        refresh();
    }

    @Scheduled(
            cron = "${warcraft-logs.refresh-cron:0 5 * * * *}",
            zone = "${warcraft-logs.refresh-zone:Europe/Zagreb}"
    )
    public void refreshScheduled() {
        refresh();
    }

    public void refresh() {
        if (!properties.collectionEnabled() || Instant.now().isBefore(properties.seasonStart())) {
            return;
        }
        if (!refreshRunning.compareAndSet(false, true)) {
            log.info("Warcraft Logs refresh skipped because another refresh is running.");
            return;
        }

        try {
            WarcraftLogsClient.RateLimit rateLimit = client.rateLimit();
            if (rateLimit.usedPercentage() >= Math.clamp(properties.rateLimitMaxPercent(), 1, 100)) {
                log.warn(
                        "Warcraft Logs refresh skipped at {}% rate-limit usage; reset in {} seconds.",
                        BigDecimal.valueOf(rateLimit.usedPercentage()).setScale(1, RoundingMode.HALF_UP),
                        rateLimit.pointsResetIn()
                );
                return;
            }
            collectIncrementalRuns();
        } catch (Exception e) {
            log.warn("Warcraft Logs hourly refresh failed: {}", e.getMessage());
        } finally {
            refreshRunning.set(false);
        }
    }

    private void collectIncrementalRuns() {
        String seasonKey = properties.seasonKey();
        Map<RunIdentity, WarcraftLogPlayerRunEntity> existingRuns = runRepository.findBySeasonKey(seasonKey)
                .stream()
                .collect(Collectors.toMap(
                        run -> new RunIdentity(run.getProfileId(), run.getReportCode(), run.getFightId()),
                        run -> run
                ));
        Map<String, ReportWork> reports = new LinkedHashMap<>();
        List<TrackedPlayer> players = trackedPlayerService.activePlayers();

        for (TrackedPlayer player : players) {
            try {
                discoverPlayerReports(player, existingRuns, reports);
                saveSnapshot(player, null, null);
            } catch (Exception e) {
                saveSnapshot(player, null, e.getMessage());
                log.warn("Could not discover Warcraft Logs reports for {}: {}", player.profileName(), e.getMessage());
            }
        }

        int savedRuns = 0;
        for (ReportWork report : reports.values()) {
            try {
                savedRuns += loadAndSaveReportEvents(report, existingRuns);
            } catch (Exception e) {
                report.participants.forEach(participant ->
                        saveSnapshot(participant.player, null, "report " + report.code + " could not be read"));
                log.warn("Could not load Warcraft Logs report {}: {}", report.code, e.getMessage());
            }
        }
        log.info(
                "Warcraft Logs season {} refresh: {} profiles, {} new or revised runs saved.",
                seasonKey,
                players.size(),
                savedRuns
        );
    }

    private void discoverPlayerReports(
            TrackedPlayer player,
            Map<RunIdentity, WarcraftLogPlayerRunEntity> existingRuns,
            Map<String, ReportWork> reports
    ) {
        Map<String, Object> variables = Map.of(
                "name", player.name(),
                "server", player.realm().toLowerCase(Locale.ROOT),
                "region", player.region().toUpperCase(Locale.ROOT),
                "limit", Math.clamp(properties.recentReportLimit(), 1, 100)
        );
        JsonNode character = client.query(CHARACTER_REPORTS_QUERY, variables)
                .path("characterData")
                .path("character");
        if (character.isMissingNode() || character.isNull()) {
            throw new IllegalStateException("character not found or has no public logs");
        }

        for (JsonNode reportNode : character.path("recentReports").path("data")) {
            DiscoveredReport discoveredReport = discoverReport(reportNode, player);
            if (discoveredReport != null) {
                addReportFights(reportNode, player, discoveredReport, existingRuns, reports);
            }
        }
    }

    private DiscoveredReport discoverReport(JsonNode reportNode, TrackedPlayer player) {
        Instant startedAt = instantFromMilliseconds(reportNode.path("startTime").asLong(0));
        if (startedAt == null || startedAt.isBefore(properties.seasonStart())) {
            return null;
        }
        String code = reportNode.path("code").asText("");
        if (code.isBlank()) {
            return null;
        }
        Map<Integer, ReportActor> actors = reportActors(reportNode.path("masterData").path("actors"));
        Integer actorId = findActorId(actors, player);
        if (actorId == null) {
            return null;
        }
        int revision = Math.max(0, reportNode.path("revision").asInt(0));
        return new DiscoveredReport(code, revision, startedAt, actorId, actors);
    }

    private void addReportFights(
            JsonNode reportNode,
            TrackedPlayer player,
            DiscoveredReport discoveredReport,
            Map<RunIdentity, WarcraftLogPlayerRunEntity> existingRuns,
            Map<String, ReportWork> reports
    ) {
        for (JsonNode fight : reportNode.path("fights")) {
            addReportFight(fight, player, discoveredReport, existingRuns, reports);
        }
    }

    private void addReportFight(
            JsonNode fight,
            TrackedPlayer player,
            DiscoveredReport discoveredReport,
            Map<RunIdentity, WarcraftLogPlayerRunEntity> existingRuns,
            Map<String, ReportWork> reports
    ) {
        int fightId = fight.path("id").asInt(0);
        int keyLevel = fight.path("keystoneLevel").asInt(0);
        if (!isEligibleFight(fight, fightId, keyLevel, discoveredReport.actorId())) {
            return;
        }

        correlateFight(fight, player, discoveredReport, fightId, keyLevel);

        RunIdentity identity = new RunIdentity(player.profileId(), discoveredReport.code(), fightId);
        WarcraftLogPlayerRunEntity existing = existingRuns.get(identity);
        if (isCurrentRun(existing, discoveredReport.revision())) {
            return;
        }

        ReportWork report = reports.computeIfAbsent(
                discoveredReport.code(),
                ignored -> new ReportWork(
                        discoveredReport.code(),
                        discoveredReport.revision(),
                        discoveredReport.startedAt()
                )
        );
        report.revision = Math.max(report.revision, discoveredReport.revision());
        report.fightIds.add(fightId);
        report.participants.add(new Participant(
                player,
                fightId,
                discoveredReport.actorId(),
                fight.path("name").asText("Unknown dungeon"),
                keyLevel,
                existing
        ));
    }

    private static boolean isEligibleFight(JsonNode fight, int fightId, int keyLevel, int actorId) {
        return fightId > 0
                && keyLevel > 0
                && containsInt(fight.path("friendlyPlayers"), actorId);
    }

    private static boolean isCurrentRun(WarcraftLogPlayerRunEntity existing, int revision) {
        return existing != null && existing.getReportRevision() >= revision && hasRankingMetrics(existing);
    }

    private void correlateFight(
            JsonNode fight,
            TrackedPlayer player,
            DiscoveredReport report,
            int fightId,
            int keyLevel
    ) {
        long startOffset = fight.path("startTime").asLong(-1);
        long endOffset = fight.path("endTime").asLong(-1);
        if (startOffset < 0 || endOffset <= startOffset) {
            return;
        }
        Set<PlayerIdentity> roster = friendlyRoster(
                fight.path("friendlyPlayers"), report.actors(), player.region()
        );
        correlationService.correlate(new LogFight(
                properties.seasonKey(), report.code(), report.revision(), fightId,
                fight.path("name").asText(""), keyLevel,
                report.startedAt().plusMillis(startOffset), report.startedAt().plusMillis(endOffset),
                endOffset - startOffset, roster
        ));
    }

    private int loadAndSaveReportEvents(
            ReportWork report,
            Map<RunIdentity, WarcraftLogPlayerRunEntity> existingRuns
    ) {
        if (report.fightIds.isEmpty()) return 0;

        JsonNode reportData = client.query(rankingsQuery(report.fightIds), Map.of("code", report.code))
                .path("reportData")
                .path("report");
        int saved = 0;
        Map<Integer, FightEvents> eventsByFight = loadFightEvents(report);
        for (Participant participant : report.participants) {
            FightEvents events = eventsByFight.get(participant.fightId);
            int interruptCount = countEventsForActor(events.interrupts(), "sourceID", participant.actorId);
            int deathCount = countDeathEventsForActor(events.deaths(), participant.actorId);
            RankingPercentiles rankingPercentiles = findRankingPercentiles(
                    reportData.path("p" + participant.fightId),
                    participant.actorId,
                    participant.player.name()
            );
            BigDecimal damagePerSecond = findDamagePerSecond(
                    reportData.path("d" + participant.fightId),
                    participant.actorId,
                    participant.player.name()
            );
            RankingMetrics rankingMetrics = new RankingMetrics(
                    rankingPercentiles.parsePercentage(),
                    rankingPercentiles.keyParsePercentage(),
                    damagePerSecond
            );
            WarcraftLogPlayerRunEntity entity = participant.existing;
            if (entity == null) {
                entity = new WarcraftLogPlayerRunEntity(
                        properties.seasonKey(),
                        participant.player.profileId(),
                        participant.player.name(),
                        report.code,
                        report.revision,
                        report.reportStartedAt,
                        participant.fightId,
                        participant.dungeonName,
                        participant.keystoneLevel,
                        interruptCount,
                        deathCount,
                        rankingMetrics.parsePercentage(),
                        rankingMetrics.keyParsePercentage(),
                        rankingMetrics.damagePerSecond()
                );
            } else {
                entity.update(
                        report.revision,
                        participant.player.name(),
                        participant.dungeonName,
                        participant.keystoneLevel,
                        interruptCount,
                        deathCount,
                        rankingMetrics.parsePercentage(),
                        rankingMetrics.keyParsePercentage(),
                        rankingMetrics.damagePerSecond()
                );
            }
            entity = runRepository.save(entity);
            existingRuns.put(
                    new RunIdentity(participant.player.profileId(), report.code, participant.fightId),
                    entity
            );
            saved++;
        }
        return saved;
    }

    static String rankingsQuery(Set<Integer> fightIds) {
        StringBuilder query = new StringBuilder(
                "query ReportRankings($code: String!) { reportData { report(code: $code) {"
        );
        for (Integer fightId : fightIds) {
            query.append(" p").append(fightId)
                    .append(": rankings(compare: Rankings, playerMetric: dps, fightIDs: [")
                    .append(fightId).append("])")
                    .append(" d").append(fightId)
                    .append(": table(dataType: DamageDone, viewBy: Source, fightIDs: [")
                    .append(fightId).append("])");
        }
        return query.append(" } } }").toString();
    }

    private Map<Integer, FightEvents> loadFightEvents(ReportWork report) {
        Map<Integer, FightEvents> events = new LinkedHashMap<>();
        for (Integer fightId : report.fightIds) {
            events.put(fightId, new FightEvents(
                    eventPager.events(report.code, fightId, EventType.INTERRUPTS),
                    eventPager.events(report.code, fightId, EventType.DEATHS)
            ));
        }
        return Map.copyOf(events);
    }

    private void saveSnapshot(TrackedPlayer player, BigDecimal parsePercentage, String error) {
        WarcraftLogProfileSnapshotEntity snapshot = snapshotRepository
                .findBySeasonKeyAndProfileId(properties.seasonKey(), player.profileId())
                .orElseGet(() -> new WarcraftLogProfileSnapshotEntity(
                        properties.seasonKey(),
                        player.profileId(),
                        player.name()
                ));
        BigDecimal effectiveParse = parsePercentage == null ? snapshot.getParsePercentage() : parsePercentage;
        snapshot.update(player.name(), effectiveParse, error);
        snapshotRepository.save(snapshot);
    }

    private static Instant instantFromMilliseconds(long milliseconds) {
        return milliseconds <= 0 ? null : Instant.ofEpochMilli(milliseconds);
    }

    private static Integer findActorId(Map<Integer, ReportActor> actors, TrackedPlayer player) {
        String expectedServer = normalizeServer(player.realm());
        for (ReportActor actor : actors.values()) {
            if (!actor.name().equalsIgnoreCase(player.name())) continue;
            String actorServer = normalizeServer(actor.server());
            if (actorServer.isBlank() || actorServer.equals(expectedServer)) {
                return actor.id();
            }
        }
        return null;
    }

    private static Map<Integer, ReportActor> reportActors(JsonNode actors) {
        Map<Integer, ReportActor> result = new LinkedHashMap<>();
        for (JsonNode actor : actors) {
            int id = actor.path("id").asInt(0);
            String name = actor.path("name").asText("");
            if (id > 0 && !name.isBlank()) {
                result.put(id, new ReportActor(id, name, actor.path("server").asText("")));
            }
        }
        return Map.copyOf(result);
    }

    private static Set<PlayerIdentity> friendlyRoster(
            JsonNode friendlyPlayers,
            Map<Integer, ReportActor> actors,
            String region
    ) {
        Set<PlayerIdentity> roster = new LinkedHashSet<>();
        for (JsonNode playerId : friendlyPlayers) {
            ReportActor actor = actors.get(playerId.asInt(0));
            if (actor != null && !actor.server().isBlank()) {
                roster.add(new PlayerIdentity(region, actor.server(), actor.name()));
            }
        }
        return Set.copyOf(roster);
    }

    private static String normalizeServer(String server) {
        return server == null ? "" : server.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }

    private static boolean containsInt(JsonNode array, int expected) {
        for (JsonNode value : array) {
            if (value.asInt() == expected) return true;
        }
        return false;
    }

    private static int countEventsForActor(Iterable<JsonNode> events, String actorField, int actorId) {
        int count = 0;
        for (JsonNode event : events) {
            if (event.path(actorField).asInt(-1) == actorId) count++;
        }
        return count;
    }

    private static int countDeathEventsForActor(Iterable<JsonNode> events, int actorId) {
        int count = 0;
        for (JsonNode event : events) {
            if (event.path("targetID").asInt(-1) == actorId
                    || (!event.has("targetID") && event.path("sourceID").asInt(-1) == actorId)) {
                count++;
            }
        }
        return count;
    }

    static RankingPercentiles findRankingPercentiles(JsonNode node, int actorId, String characterName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return RankingPercentiles.unavailable();
        }
        RankingPercentiles directMatch = rankingPercentilesForPlayer(node, actorId, characterName);
        if (directMatch.available()) {
            return directMatch;
        }
        var children = node.elements();
        while (children.hasNext()) {
            RankingPercentiles nestedMatch = findRankingPercentiles(children.next(), actorId, characterName);
            if (nestedMatch.available()) {
                return nestedMatch;
            }
        }
        return RankingPercentiles.unavailable();
    }

    private static RankingPercentiles rankingPercentilesForPlayer(
            JsonNode node,
            int actorId,
            String characterName
    ) {
        JsonNode rankPercent = node.path("rankPercent");
        JsonNode bracketPercent = node.path("bracketPercent");
        if (!rankingBelongsToPlayer(node, actorId, characterName)
                || !rankPercent.isNumber() || !bracketPercent.isNumber()) {
            return RankingPercentiles.unavailable();
        }
        return new RankingPercentiles(rankPercent.decimalValue(), bracketPercent.decimalValue());
    }

    static BigDecimal findDamagePerSecond(JsonNode table, int actorId, String characterName) {
        JsonNode tableData = unwrapTableData(table);
        JsonNode totalTime = tableData.path("totalTime");
        if (!totalTime.isNumber() || totalTime.decimalValue().signum() <= 0) {
            return null;
        }
        BigDecimal totalDamage = findTotalDamage(tableData.path("entries"), actorId, characterName);
        if (totalDamage == null) {
            return null;
        }
        return totalDamage.multiply(BigDecimal.valueOf(1_000))
                .divide(totalTime.decimalValue(), 1, RoundingMode.HALF_UP);
    }

    private static JsonNode unwrapTableData(JsonNode table) {
        if (table == null || table.isMissingNode() || table.isNull()) {
            return MissingNode.getInstance();
        }
        JsonNode data = table.path("data");
        return data.isObject() ? data : table;
    }

    private static BigDecimal findTotalDamage(JsonNode node, int actorId, String characterName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode total = node.path("total");
        if (total.isNumber() && rankingBelongsToPlayer(node, actorId, characterName)) {
            return total.decimalValue();
        }
        var children = node.elements();
        while (children.hasNext()) {
            BigDecimal nestedTotal = findTotalDamage(children.next(), actorId, characterName);
            if (nestedTotal != null) {
                return nestedTotal;
            }
        }
        return null;
    }

    private static boolean rankingBelongsToPlayer(JsonNode ranking, int actorId, String characterName) {
        if (ranking.path("id").asInt(-1) == actorId
                || ranking.path("actorID").asInt(-1) == actorId
                || ranking.path("sourceID").asInt(-1) == actorId) {
            return true;
        }
        return ranking.path("name").asText("").equalsIgnoreCase(characterName);
    }

    private static BigDecimal average(int total, int count) {
        if (count == 0) return null;
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) return null;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 1, RoundingMode.HALF_UP);
    }

    private static List<BigDecimal> nonNullMetrics(
            List<WarcraftLogPlayerRunEntity> runs,
            java.util.function.Function<WarcraftLogPlayerRunEntity, BigDecimal> metric
    ) {
        return runs.stream().map(metric).filter(value -> value != null).toList();
    }

    private static boolean hasRankingMetrics(WarcraftLogPlayerRunEntity run) {
        return run.getMetricsVersion() >= WarcraftLogPlayerRunEntity.CURRENT_METRICS_VERSION
                && run.getParsePercentage() != null
                && run.getKeyParsePercentage() != null
                && run.getDamagePerSecond() != null;
    }

    public record PlayerStatistics(
            String profileName,
            String characterName,
            int dungeonRuns,
            int rankedDungeonRuns,
            BigDecimal averageInterrupts,
            BigDecimal averageDeaths,
            BigDecimal averageParsePercentage,
            BigDecimal averageKeyParsePercentage,
            BigDecimal averageDamagePerSecond,
            String error
    ) {
    }

    record RankingMetrics(
            BigDecimal parsePercentage,
            BigDecimal keyParsePercentage,
            BigDecimal damagePerSecond
    ) {
    }

    record RankingPercentiles(BigDecimal parsePercentage, BigDecimal keyParsePercentage) {
        private static RankingPercentiles unavailable() {
            return new RankingPercentiles(null, null);
        }

        private boolean available() {
            return parsePercentage != null && keyParsePercentage != null;
        }
    }

    private record RunIdentity(long profileId, String reportCode, int fightId) {
    }

    private record DiscoveredReport(
            String code,
            int revision,
            Instant startedAt,
            int actorId,
            Map<Integer, ReportActor> actors
    ) {
    }

    private record ReportActor(int id, String name, String server) {
    }

    private record FightEvents(List<JsonNode> interrupts, List<JsonNode> deaths) {
    }

    private record Participant(
            TrackedPlayer player,
            int fightId,
            int actorId,
            String dungeonName,
            int keystoneLevel,
            WarcraftLogPlayerRunEntity existing
    ) {
    }

    private static final class ReportWork {
        private final String code;
        private int revision;
        private final Instant reportStartedAt;
        private final Set<Integer> fightIds = new LinkedHashSet<>();
        private final Set<Participant> participants = new LinkedHashSet<>();

        private ReportWork(String code, int revision, Instant reportStartedAt) {
            this.code = code;
            this.revision = revision;
            this.reportStartedAt = reportStartedAt;
        }
    }
}
