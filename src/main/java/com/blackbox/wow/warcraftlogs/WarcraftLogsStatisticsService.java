package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.entity.WarcraftLogPlayerRunEntity;
import com.blackbox.wow.entity.WarcraftLogProfileSnapshotEntity;
import com.blackbox.wow.repository.WarcraftLogPlayerRunRepository;
import com.blackbox.wow.repository.WarcraftLogProfileSnapshotRepository;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final AtomicBoolean refreshRunning = new AtomicBoolean();

    public WarcraftLogsStatisticsService(
            WarcraftLogsClient client,
            WarcraftLogsProperties properties,
            TrackedPlayerService trackedPlayerService,
            WarcraftLogPlayerRunRepository runRepository,
            WarcraftLogProfileSnapshotRepository snapshotRepository
    ) {
        this.client = client;
        this.properties = properties;
        this.trackedPlayerService = trackedPlayerService;
        this.runRepository = runRepository;
        this.snapshotRepository = snapshotRepository;
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
            List<BigDecimal> runParses = runs.stream()
                    .map(WarcraftLogPlayerRunEntity::getParsePercentage)
                    .filter(value -> value != null)
                    .toList();
            WarcraftLogProfileSnapshotEntity snapshot = snapshotsByProfile.get(player.profileId());
            result.add(new PlayerStatistics(
                    player.profileName(),
                    player.name(),
                    runs.size(),
                    runParses.size(),
                    average(interrupts, runs.size()),
                    average(deaths, runs.size()),
                    average(runParses),
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
        Integer actorId = findActorId(reportNode.path("masterData").path("actors"), player);
        if (actorId == null) {
            return null;
        }
        int revision = Math.max(0, reportNode.path("revision").asInt(0));
        return new DiscoveredReport(code, revision, startedAt, actorId);
    }

    private static void addReportFights(
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

    private static void addReportFight(
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
        return existing != null
                && existing.getReportRevision() >= revision
                && existing.getParsePercentage() != null;
    }

    private int loadAndSaveReportEvents(
            ReportWork report,
            Map<RunIdentity, WarcraftLogPlayerRunEntity> existingRuns
    ) {
        if (report.fightIds.isEmpty()) return 0;

        StringBuilder query = new StringBuilder("query ReportEvents($code: String!) { reportData { report(code: $code) {");
        for (Integer fightId : report.fightIds) {
            query.append(" i").append(fightId)
                    .append(": events(dataType: Interrupts, fightIDs: [").append(fightId)
                    .append("], limit: 10000) { data }");
            query.append(" d").append(fightId)
                    .append(": events(dataType: Deaths, fightIDs: [").append(fightId)
                    .append("], limit: 10000) { data }");
            query.append(" p").append(fightId)
                    .append(": rankings(compare: Parses, fightIDs: [").append(fightId)
                    .append("])");
        }
        query.append(" } } }");

        JsonNode reportData = client.query(query.toString(), Map.of("code", report.code))
                .path("reportData")
                .path("report");
        int saved = 0;
        for (Participant participant : report.participants) {
            JsonNode interrupts = reportData.path("i" + participant.fightId).path("data");
            JsonNode deaths = reportData.path("d" + participant.fightId).path("data");
            int interruptCount = countEventsForActor(interrupts, "sourceID", participant.actorId);
            int deathCount = countDeathEventsForActor(deaths, participant.actorId);
            BigDecimal parsePercentage = findParsePercentage(
                    reportData.path("p" + participant.fightId),
                    participant.actorId,
                    participant.player.name()
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
                        parsePercentage
                );
            } else {
                entity.update(
                        report.revision,
                        participant.player.name(),
                        participant.dungeonName,
                        participant.keystoneLevel,
                        interruptCount,
                        deathCount,
                        parsePercentage
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

    private static Integer findActorId(JsonNode actors, TrackedPlayer player) {
        String expectedServer = normalizeServer(player.realm());
        for (JsonNode actor : actors) {
            if (!actor.path("name").asText("").equalsIgnoreCase(player.name())) continue;
            String actorServer = normalizeServer(actor.path("server").asText(""));
            if (actorServer.isBlank() || actorServer.equals(expectedServer)) {
                int id = actor.path("id").asInt(0);
                return id > 0 ? id : null;
            }
        }
        return null;
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

    private static int countEventsForActor(JsonNode events, String actorField, int actorId) {
        int count = 0;
        for (JsonNode event : events) {
            if (event.path(actorField).asInt(-1) == actorId) count++;
        }
        return count;
    }

    private static int countDeathEventsForActor(JsonNode events, int actorId) {
        int count = 0;
        for (JsonNode event : events) {
            if (event.path("targetID").asInt(-1) == actorId
                    || (!event.has("targetID") && event.path("sourceID").asInt(-1) == actorId)) {
                count++;
            }
        }
        return count;
    }

    private static BigDecimal findParsePercentage(JsonNode node, int actorId, String characterName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        BigDecimal directMatch = parsePercentageForPlayer(node, actorId, characterName);
        if (directMatch != null) {
            return directMatch;
        }
        var children = node.elements();
        while (children.hasNext()) {
            BigDecimal nestedMatch = findParsePercentage(children.next(), actorId, characterName);
            if (nestedMatch != null) {
                return nestedMatch;
            }
        }
        return null;
    }

    private static BigDecimal parsePercentageForPlayer(JsonNode node, int actorId, String characterName) {
        JsonNode rankPercent = node.path("rankPercent");
        return rankPercent.isNumber() && rankingBelongsToPlayer(node, actorId, characterName)
                ? rankPercent.decimalValue()
                : null;
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

    public record PlayerStatistics(
            String profileName,
            String characterName,
            int dungeonRuns,
            int parsedDungeonRuns,
            BigDecimal averageInterrupts,
            BigDecimal averageDeaths,
            BigDecimal averageParsePercentage,
            String error
    ) {
    }

    private record RunIdentity(long profileId, String reportCode, int fightId) {
    }

    private record DiscoveredReport(String code, int revision, Instant startedAt, int actorId) {
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
