package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.entity.WarcraftLogPlayerRunEntity;
import com.blackbox.wow.entity.WarcraftLogProfileSnapshotEntity;
import com.blackbox.wow.repository.WarcraftLogPlayerRunRepository;
import com.blackbox.wow.repository.WarcraftLogProfileSnapshotRepository;
import com.blackbox.wow.repository.WarcraftLogItemLevelRepository;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.service.CombatKeyLevelService;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import com.blackbox.wow.service.MPlusRunCorrelationService;
import com.blackbox.wow.helper.MPlusRunMatcher.LogFight;
import com.blackbox.wow.helper.MPlusRunMatcher.PlayerIdentity;
import com.blackbox.wow.warcraftlogs.WarcraftLogsEventPager.EventType;
import com.blackbox.wow.warcraftlogs.WarcraftLogsEventParser.FightWindow;
import com.blackbox.wow.warcraftlogs.WarcraftLogsEventParser.ReportActor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.blackbox.wow.warcraftlogs.WarcraftLogsEventParser.countDeathEventsForActor;
import static com.blackbox.wow.warcraftlogs.WarcraftLogsEventParser.countEventsForActor;
import static com.blackbox.wow.warcraftlogs.WarcraftLogsEventParser.fightWindow;
import static com.blackbox.wow.warcraftlogs.WarcraftLogsEventParser.findActorId;
import static com.blackbox.wow.warcraftlogs.WarcraftLogsEventParser.findFriendlyItemLevel;
import static com.blackbox.wow.warcraftlogs.WarcraftLogsEventParser.friendlyRoster;
import static com.blackbox.wow.warcraftlogs.WarcraftLogsEventParser.isEligibleFight;
import static com.blackbox.wow.warcraftlogs.WarcraftLogsEventParser.reportActors;
import static com.blackbox.wow.warcraftlogs.WarcraftLogsEventParser.sumAvoidableDamageForActor;

@Service
@Slf4j
public class WarcraftLogsCollectionService {

    private static final int MAX_REPORT_PAGES = 100;
    private static final Duration DEFAULT_RATE_LIMIT_BACKOFF = Duration.ofHours(1);
    private static final BigDecimal MAX_PERCENTILE = BigDecimal.valueOf(100);
    private static final String NAME_FIELD = "name";
    private static final String SERVER_FIELD = "server";
    private static final String REGION_FIELD = "region";
    private static final String CHARACTER_DATA_FIELD = "characterData";
    private static final String CHARACTER_FIELD = "character";
    private static final String REPORT_DATA_FIELD = "reportData";
    private static final String REPORT_FIELD = "report";
    private static final String REPORT_CODE_FIELD = "code";
    private static final String REPORT_REVISION_FIELD = "revision";
    private static final String MASTER_DATA_FIELD = "masterData";
    private static final String ACTORS_FIELD = "actors";
    private static final String SOURCE_ID_FIELD = "sourceID";
    private static final String CHARACTER_REPORTS_QUERY = """
            query CharacterReports(
              $name: String!, $server: String!, $region: String!, $limit: Int!, $page: Int!
            ) {
              characterData {
                character(name: $name, serverSlug: $server, serverRegion: $region) {
                  name
                  recentReports(limit: $limit, page: $page) {
                    has_more_pages
                    data {
                      code
                      revision
                      startTime
                      endTime
                      fights {
                        id
                        name
                        encounterID
                        keystoneLevel
                        keystoneTime
                        keystoneBonus
                        startTime
                        endTime
                        friendlyPlayers
                        friendlyItemLevels
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
    private static final String STORED_REPORT_QUERY = """
            query StoredReport($code: String!) {
              reportData {
                report(code: $code) {
                  revision
                  fights {
                    id
                    keystoneTime
                    keystoneBonus
                    startTime
                    endTime
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
            """;
    private final WarcraftLogsClient client;
    private final WarcraftLogsProperties properties;
    private final TrackedPlayerService trackedPlayerService;
    private final WarcraftLogPlayerRunRepository runRepository;
    private final WarcraftLogProfileSnapshotRepository snapshotRepository;
    private final MPlusRunCorrelationService correlationService;
    private final WarcraftLogsEventPager eventPager;
    private final WarcraftLogItemLevelRepository itemLevelRepository;
    private final AtomicBoolean refreshRunning = new AtomicBoolean();
    private final AtomicReference<Instant> rateLimitBlockedUntil = new AtomicReference<>();

    public WarcraftLogsCollectionService(
            WarcraftLogsClient client,
            WarcraftLogsProperties properties,
            TrackedPlayerService trackedPlayerService,
            WarcraftLogPlayerRunRepository runRepository,
            WarcraftLogProfileSnapshotRepository snapshotRepository,
            MPlusRunCorrelationService correlationService,
            WarcraftLogsEventPager eventPager,
            WarcraftLogItemLevelRepository itemLevelRepository
    ) {
        this.client = client;
        this.properties = properties;
        this.trackedPlayerService = trackedPlayerService;
        this.runRepository = runRepository;
        this.snapshotRepository = snapshotRepository;
        this.correlationService = correlationService;
        this.eventPager = eventPager;
        this.itemLevelRepository = itemLevelRepository;
    }

    public boolean isRefreshRunning() { return refreshRunning.get(); }

    public void refresh() {
        Instant refreshStartedAt = Instant.now();
        if (!properties.collectionEnabled() || refreshStartedAt.isBefore(properties.seasonStart())) {
            return;
        }
        Instant blockedUntil = rateLimitBlockedUntil.get();
        if (blockedUntil != null && refreshStartedAt.isBefore(blockedUntil)) {
            log.info("Warcraft Logs refresh deferred until {} after rate limiting.", blockedUntil);
            return;
        }
        if (!refreshRunning.compareAndSet(false, true)) {
            log.info("Warcraft Logs refresh skipped because another refresh is running.");
            return;
        }

        try {
            WarcraftLogsClient.RateLimit rateLimit = client.rateLimit();
            if (rateLimit.usedPercentage() >= Math.clamp(properties.rateLimitMaxPercent(), 1, 100)) {
                Instant retryAt = blockRefreshes(rateLimitResetDelay(rateLimit.pointsResetIn()));
                log.warn(
                        "Warcraft Logs refresh skipped at {}% rate-limit usage; retry after {}.",
                        BigDecimal.valueOf(rateLimit.usedPercentage()).setScale(1, RoundingMode.HALF_UP),
                        retryAt
                );
                return;
            }
            rateLimitBlockedUntil.set(null);
            collectIncrementalRuns();
        } catch (WarcraftLogsClient.RateLimitExceededException rateLimited) {
            Instant retryAt = blockRefreshes(rateLimited.retryAfter());
            log.warn("Warcraft Logs refresh paused until {} after a 429 response.", retryAt);
        } catch (Exception e) {
            log.warn("Warcraft Logs refresh failed: {}", e.getMessage());
        } finally {
            refreshRunning.set(false);
        }
    }

    private Instant blockRefreshes(Duration delay) {
        Instant blockedUntil = Instant.now().plus(delay);
        rateLimitBlockedUntil.set(blockedUntil);
        return blockedUntil;
    }

    private static Duration rateLimitResetDelay(int pointsResetIn) {
        return pointsResetIn > 0 ? Duration.ofSeconds(pointsResetIn) : DEFAULT_RATE_LIMIT_BACKOFF;
    }

    private void collectIncrementalRuns() {
        String seasonKey = properties.seasonKey();
        List<WarcraftLogPlayerRunEntity> storedRuns = runRepository.findBySeasonKey(seasonKey);
        ExistingRunIndex existingRuns = ExistingRunIndex.from(storedRuns);
        Map<ReportFingerprint, ReportWork> reports = new LinkedHashMap<>();
        Map<ReportFingerprint, String> canonicalReportCodes = new LinkedHashMap<>();
        Map<DailyItemLevelKey, DailyItemLevelCandidate> itemLevelCandidates = new LinkedHashMap<>();
        List<TrackedPlayer> players = trackedPlayerService.activePlayers();

        for (TrackedPlayer player : players) {
            try {
                discoverPlayerReports(
                        player, existingRuns, reports, canonicalReportCodes, itemLevelCandidates
                );
                saveSnapshot(player, null, null);
            } catch (Exception e) {
                rethrowIfRateLimited(e);
                saveSnapshot(player, null, e.getMessage());
                log.warn("Could not discover Warcraft Logs reports for {}: {}", player.profileName(), e.getMessage());
            }
        }

        int savedItemLevels = backfillDailyItemLevels(itemLevelCandidates.values());

        int savedRuns = 0;
        for (ReportWork report : reports.values()) {
            try {
                savedRuns += loadAndSaveReportEvents(report, existingRuns);
            } catch (Exception e) {
                rethrowIfRateLimited(e);
                report.participants.forEach(participant ->
                        saveSnapshot(participant.player, null, "report " + report.code + " could not be read"));
                log.warn("Could not load Warcraft Logs report {}: {}", report.code, e.getMessage());
            }
        }
        savedRuns += backfillStoredFightWindows(storedRuns, players, existingRuns);
        savedRuns += backfillStoredRunMetrics(storedRuns, players, existingRuns);
        log.info(
                "Warcraft Logs season {} refresh: {} profiles, {} new or revised runs and "
                        + "{} daily item levels saved.",
                seasonKey,
                players.size(),
                savedRuns,
                savedItemLevels
        );
    }

    private int backfillDailyItemLevels(Collection<DailyItemLevelCandidate> candidates) {
        int saved = 0;
        for (DailyItemLevelCandidate candidate : candidates) {
            if (itemLevelRepository.hasSnapshot(
                    candidate.player.profileId(), candidate.player.name(), candidate.observedDate
            )) {
                continue;
            }
            try {
                itemLevelRepository.save(
                        candidate.player.profileId(),
                        candidate.player.name(),
                        candidate.observedDate,
                        candidate.observedAt,
                        candidate.itemLevel,
                        candidate.reportCode,
                        candidate.fightId
                );
                saved++;
            } catch (RuntimeException failure) {
                log.warn(
                        "Could not backfill Warcraft Logs item level for profile {} on {} ({})",
                        candidate.player.profileName(),
                        candidate.observedDate,
                        failure.getClass().getSimpleName()
                );
            }
        }
        return saved;
    }

    private int backfillStoredRunMetrics(
            List<WarcraftLogPlayerRunEntity> storedRuns,
            List<TrackedPlayer> players,
            ExistingRunIndex existingRuns
    ) {
        Map<Long, TrackedPlayer> playersById = players.stream()
                .collect(Collectors.toMap(TrackedPlayer::profileId, Function.identity()));
        Map<String, List<WarcraftLogPlayerRunEntity>> staleRunsByReport = storedRuns.stream()
                .filter(run -> run.getKeystoneLevel() >= CombatKeyLevelService.MIN_LEVEL)
                .filter(run -> !hasCurrentMetricsVersion(run) || run.getTimed() == null)
                .filter(run -> playersById.containsKey(run.getProfileId()))
                .collect(Collectors.groupingBy(
                        WarcraftLogPlayerRunEntity::getReportCode,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        int savedRuns = 0;
        for (Map.Entry<String, List<WarcraftLogPlayerRunEntity>> entry : staleRunsByReport.entrySet()) {
            try {
                ReportWork report = storedReportWork(entry.getKey(), entry.getValue(), playersById);
                savedRuns += loadAndSaveReportEvents(report, existingRuns);
            } catch (RuntimeException exception) {
                rethrowIfRateLimited(exception);
                log.warn("Could not backfill Warcraft Logs report {}: {}", entry.getKey(), exception.getMessage());
            }
        }
        return savedRuns;
    }

    private int backfillStoredFightWindows(
            List<WarcraftLogPlayerRunEntity> storedRuns,
            List<TrackedPlayer> players,
            ExistingRunIndex existingRuns
    ) {
        Set<Long> activeProfileIds = players.stream()
                .map(TrackedPlayer::profileId)
                .collect(Collectors.toSet());
        Map<String, List<WarcraftLogPlayerRunEntity>> runsByReport = storedRuns.stream()
                .filter(run -> run.getKeystoneLevel() >= CombatKeyLevelService.MIN_LEVEL)
                .filter(WarcraftLogsCollectionService::needsOnlyFightWindowBackfill)
                .filter(run -> activeProfileIds.contains(run.getProfileId()))
                .collect(Collectors.groupingBy(
                        WarcraftLogPlayerRunEntity::getReportCode,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        int savedRuns = 0;
        for (Map.Entry<String, List<WarcraftLogPlayerRunEntity>> entry : runsByReport.entrySet()) {
            try {
                JsonNode reportData = loadStoredReport(entry.getKey());
                savedRuns += saveStoredFightWindows(
                        reportData.path("fights"), entry.getValue(), existingRuns
                );
            } catch (RuntimeException exception) {
                rethrowIfRateLimited(exception);
                log.warn(
                        "Could not backfill Warcraft Logs fight times for report {}: {}",
                        entry.getKey(), exception.getMessage()
                );
            }
        }
        return savedRuns;
    }

    private static void rethrowIfRateLimited(Exception failure) {
        if (failure instanceof WarcraftLogsClient.RateLimitExceededException rateLimited) {
            throw rateLimited;
        }
    }

    private int saveStoredFightWindows(
            JsonNode fights,
            List<WarcraftLogPlayerRunEntity> runs,
            ExistingRunIndex existingRuns
    ) {
        int savedRuns = 0;
        for (WarcraftLogPlayerRunEntity run : runs) {
            FightWindow fightWindow = fightWindow(
                    fightById(fights, run.getFightId()), run.getReportStartedAt()
            );
            if (fightWindow == null) {
                continue;
            }
            run.recordFightWindow(fightWindow.startedAt(), fightWindow.endedAt());
            WarcraftLogPlayerRunEntity savedRun = runRepository.save(run);
            existingRuns.remember(savedRun);
            savedRuns++;
        }
        return savedRuns;
    }

    private static boolean needsOnlyFightWindowBackfill(WarcraftLogPlayerRunEntity run) {
        return hasCurrentMetricsVersion(run)
                && run.getTimed() != null
                && (run.getFightStartedAt() == null || run.getFightEndedAt() == null);
    }

    private ReportWork storedReportWork(
            String reportCode,
            List<WarcraftLogPlayerRunEntity> storedRuns,
            Map<Long, TrackedPlayer> playersById
    ) {
        JsonNode reportData = loadStoredReport(reportCode);
        Map<Integer, ReportActor> actors = reportActors(
                reportData.path(MASTER_DATA_FIELD).path(ACTORS_FIELD)
        );
        int reportRevision = reportData.path(REPORT_REVISION_FIELD).asInt(0);
        ReportWork report = new ReportWork(
                reportCode,
                reportRevision,
                storedRuns.getFirst().getReportStartedAt()
        );
        for (WarcraftLogPlayerRunEntity run : storedRuns) {
            TrackedPlayer player = playersById.get(run.getProfileId());
            Integer actorId = findActorId(actors, player);
            JsonNode fight = fightById(reportData.path("fights"), run.getFightId());
            long keystoneTimeMs = fight.path("keystoneTime").asLong(0);
            FightWindow fightWindow = fightWindow(fight, run.getReportStartedAt());
            if (actorId == null || keystoneTimeMs <= 0 || fightWindow == null) {
                continue;
            }
            report.revision = Math.max(report.revision, run.getReportRevision());
            report.fightIds.add(run.getFightId());
            report.participants.add(new Participant(
                    player,
                    run.getFightId(),
                    actorId,
                    run.getDungeonName(),
                    run.getKeystoneLevel(),
                    keystoneTimeMs,
                    fight.path("keystoneBonus").asInt(0) > 0,
                    fightWindow.startedAt(),
                    fightWindow.endedAt(),
                    run
            ));
        }
        return report;
    }

    private JsonNode loadStoredReport(String reportCode) {
        JsonNode reportData = client.query(STORED_REPORT_QUERY, Map.of(REPORT_CODE_FIELD, reportCode))
                .path(REPORT_DATA_FIELD)
                .path(REPORT_FIELD);
        if (reportData.isMissingNode() || reportData.isNull()) {
            throw new IllegalStateException("stored report is unavailable");
        }
        return reportData;
    }

    private static JsonNode fightById(JsonNode fights, int fightId) {
        for (JsonNode fight : fights) {
            if (fight.path("id").asInt(0) == fightId) {
                return fight;
            }
        }
        return MissingNode.getInstance();
    }

    private void discoverPlayerReports(
            TrackedPlayer player,
            ExistingRunIndex existingRuns,
            Map<ReportFingerprint, ReportWork> reports,
            Map<ReportFingerprint, String> canonicalReportCodes,
            Map<DailyItemLevelKey, DailyItemLevelCandidate> itemLevelCandidates
    ) {
        for (int page = 1; page <= MAX_REPORT_PAGES; page++) {
            JsonNode character = loadCharacterReportsPage(player, page);
            JsonNode recentReports = character.path("recentReports");
            JsonNode reportNodes = recentReports.path("data");
            for (JsonNode reportNode : reportNodes) {
                DiscoveredReport discoveredReport = discoverReport(reportNode, player);
                if (discoveredReport != null
                        && isCanonicalReport(discoveredReport, canonicalReportCodes)) {
                    discoverDailyItemLevelCandidate(
                            reportNode, player, discoveredReport, itemLevelCandidates
                    );
                    addReportFights(reportNode, player, discoveredReport, existingRuns, reports);
                }
            }
            if (!recentReports.path("has_more_pages").asBoolean(false)
                    || containsReportBeforeSeason(reportNodes)) {
                return;
            }
        }
        throw new IllegalStateException("Warcraft Logs report pagination exceeded the safety limit");
    }

    private JsonNode loadCharacterReportsPage(TrackedPlayer player, int page) {
        Map<String, Object> variables = Map.of(
                NAME_FIELD, player.name(),
                SERVER_FIELD, player.realm().toLowerCase(Locale.ROOT),
                REGION_FIELD, player.region().toUpperCase(Locale.ROOT),
                "limit", Math.clamp(properties.recentReportLimit(), 1, 100),
                "page", page
        );
        JsonNode character = client.query(CHARACTER_REPORTS_QUERY, variables)
                .path(CHARACTER_DATA_FIELD)
                .path(CHARACTER_FIELD);
        if (character.isMissingNode() || character.isNull()) {
            throw new IllegalStateException("character not found or has no public logs");
        }
        return character;
    }

    private boolean containsReportBeforeSeason(JsonNode reportNodes) {
        for (JsonNode reportNode : reportNodes) {
            Instant startedAt = instantFromMilliseconds(reportNode.path("startTime").asLong(0));
            if (startedAt != null && startedAt.isBefore(properties.seasonStart())) {
                return true;
            }
        }
        return false;
    }

    private static void discoverDailyItemLevelCandidate(
            JsonNode reportNode,
            TrackedPlayer player,
            DiscoveredReport report,
            Map<DailyItemLevelKey, DailyItemLevelCandidate> candidates
    ) {
        for (JsonNode fight : reportNode.path("fights")) {
            int fightId = fight.path("id").asInt(0);
            int encounterId = fight.path("encounterID").asInt(0);
            int keystoneLevel = fight.path("keystoneLevel").asInt(0);
            long startOffset = fight.path("startTime").asLong(-1);
            BigDecimal itemLevel = findFriendlyItemLevel(fight, report.actorId());
            if (fightId <= 0 || startOffset < 0 || itemLevel == null
                    || (encounterId <= 0 && keystoneLevel <= 0)) {
                continue;
            }
            Instant observedAt = report.startedAt().plusMillis(startOffset);
            LocalDate observedDate = observedAt.atZone(ZoneOffset.UTC).toLocalDate();
            DailyItemLevelKey key = new DailyItemLevelKey(player.profileId(), observedDate);
            DailyItemLevelCandidate candidate = new DailyItemLevelCandidate(
                    player,
                    report.code(),
                    fightId,
                    report.actorId(),
                    observedAt,
                    observedDate,
                    itemLevel
            );
            candidates.merge(key, candidate, WarcraftLogsCollectionService::earlierCandidate);
        }
    }

    private static DailyItemLevelCandidate earlierCandidate(
            DailyItemLevelCandidate left,
            DailyItemLevelCandidate right
    ) {
        return left.observedAt.isBefore(right.observedAt) ? left : right;
    }

    private static boolean isCanonicalReport(
            DiscoveredReport report,
            Map<ReportFingerprint, String> canonicalReportCodes
    ) {
        ReportFingerprint fingerprint = new ReportFingerprint(report.startedAt());
        String canonicalCode = canonicalReportCodes.putIfAbsent(fingerprint, report.code());
        return canonicalCode == null || canonicalCode.equals(report.code());
    }

    private DiscoveredReport discoverReport(JsonNode reportNode, TrackedPlayer player) {
        Instant startedAt = instantFromMilliseconds(reportNode.path("startTime").asLong(0));
        Instant endedAt = instantFromMilliseconds(reportNode.path("endTime").asLong(0));
        if (startedAt == null || endedAt == null || endedAt.isBefore(startedAt)
                || startedAt.isBefore(properties.seasonStart())) {
            return null;
        }
        String code = reportNode.path(REPORT_CODE_FIELD).asText("");
        if (code.isBlank()) {
            return null;
        }
        Map<Integer, ReportActor> actors = reportActors(
                reportNode.path(MASTER_DATA_FIELD).path(ACTORS_FIELD)
        );
        Integer actorId = findActorId(actors, player);
        if (actorId == null) {
            return null;
        }
        int revision = Math.max(0, reportNode.path(REPORT_REVISION_FIELD).asInt(0));
        return new DiscoveredReport(code, revision, startedAt, actorId, actors);
    }

    private void addReportFights(
            JsonNode reportNode,
            TrackedPlayer player,
            DiscoveredReport discoveredReport,
            ExistingRunIndex existingRuns,
            Map<ReportFingerprint, ReportWork> reports
    ) {
        for (JsonNode fight : reportNode.path("fights")) {
            addReportFight(fight, player, discoveredReport, existingRuns, reports);
        }
    }

    private void addReportFight(
            JsonNode fight,
            TrackedPlayer player,
            DiscoveredReport discoveredReport,
            ExistingRunIndex existingRuns,
            Map<ReportFingerprint, ReportWork> reports
    ) {
        int fightId = fight.path("id").asInt(0);
        int keyLevel = fight.path("keystoneLevel").asInt(0);
        long keystoneTimeMs = fight.path("keystoneTime").asLong(0);
        boolean timed = fight.path("keystoneBonus").asInt(0) > 0;
        FightWindow fightWindow = fightWindow(fight, discoveredReport.startedAt());
        if (!isEligibleFight(
                fight,
                fightId,
                keyLevel,
                discoveredReport.actorId(),
                CombatKeyLevelService.MIN_LEVEL
        )
                || fightWindow == null) {
            return;
        }

        correlateFight(fight, player, discoveredReport, fightId, keyLevel, fightWindow);

        WarcraftLogPlayerRunEntity existing = existingRuns.find(player, discoveredReport, fightId);
        if (isCurrentRun(existing, discoveredReport.revision())) {
            return;
        }

        ReportFingerprint fingerprint = new ReportFingerprint(discoveredReport.startedAt());
        ReportWork report = reports.computeIfAbsent(
                fingerprint,
                ignored -> new ReportWork(
                        discoveredReport.code(),
                        discoveredReport.revision(),
                        discoveredReport.startedAt()
                )
        );
        if (report.code.equals(discoveredReport.code())) {
            report.revision = Math.max(report.revision, discoveredReport.revision());
        }
        report.fightIds.add(fightId);
        report.participants.add(new Participant(
                player,
                fightId,
                discoveredReport.actorId(),
                fight.path(NAME_FIELD).asText("Unknown dungeon"),
                keyLevel,
                keystoneTimeMs,
                timed,
                fightWindow.startedAt(),
                fightWindow.endedAt(),
                existing
        ));
    }

    private static boolean isCurrentRun(WarcraftLogPlayerRunEntity existing, int revision) {
        return existing != null
                && existing.getReportRevision() >= revision
                && hasCompleteMetrics(existing)
                && existing.getTimed() != null;
    }

    private void correlateFight(
            JsonNode fight,
            TrackedPlayer player,
            DiscoveredReport report,
            int fightId,
            int keyLevel,
            FightWindow fightWindow
    ) {
        Set<PlayerIdentity> roster = friendlyRoster(
                fight.path("friendlyPlayers"), report.actors(), player.region()
        );
        correlationService.correlate(new LogFight(
                properties.seasonKey(), report.code(), report.revision(), fightId,
                fight.path(NAME_FIELD).asText(""), keyLevel,
                fightWindow.startedAt(), fightWindow.endedAt(), fightWindow.durationMs(), roster
        ));
    }

    private int loadAndSaveReportEvents(
            ReportWork report,
            ExistingRunIndex existingRuns
    ) {
        if (report.fightIds.isEmpty()) return 0;

        JsonNode reportData = client.query(
                        WarcraftLogsRankingParser.rankingsQuery(report.fightIds),
                        Map.of(REPORT_CODE_FIELD, report.code)
                )
                .path(REPORT_DATA_FIELD)
                .path(REPORT_FIELD);
        int saved = 0;
        Map<Integer, FightEvents> eventsByFight = loadFightEvents(report);
        for (Participant participant : report.participants) {
            FightEvents events = eventsByFight.get(participant.fightId);
            int interruptCount = countEventsForActor(events.interrupts(), SOURCE_ID_FIELD, participant.actorId);
            int deathCount = countDeathEventsForActor(events.deaths(), participant.actorId);
            BigDecimal avoidableDamage = sumAvoidableDamageForActor(
                    events.damageTaken(), participant.actorId, participant.dungeonName
            );
            WarcraftLogsRankingParser.RankingMetrics rankingMetrics =
                    WarcraftLogsRankingParser.findMetrics(
                            reportData.path("p" + participant.fightId),
                            reportData.path("d" + participant.fightId),
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
            entity.recordAvoidableDamage(avoidableDamage);
            entity.recordCompletion(participant.keystoneTimeMs, participant.timed);
            entity.recordFightWindow(participant.fightStartedAt, participant.fightEndedAt);
            entity = runRepository.save(entity);
            existingRuns.remember(entity);
            saved++;
        }
        return saved;
    }

    private Map<Integer, FightEvents> loadFightEvents(ReportWork report) {
        Map<Integer, FightEvents> events = new LinkedHashMap<>();
        for (Integer fightId : report.fightIds) {
            events.put(fightId, new FightEvents(
                    eventPager.events(report.code, fightId, EventType.INTERRUPTS),
                    eventPager.events(report.code, fightId, EventType.DEATHS),
                    eventPager.events(report.code, fightId, EventType.DAMAGE_TAKEN)
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

    private static boolean hasCurrentMetricsVersion(WarcraftLogPlayerRunEntity run) {
        return run.getMetricsVersion() >= WarcraftLogPlayerRunEntity.CURRENT_METRICS_VERSION;
    }

    private static boolean hasCompleteMetrics(WarcraftLogPlayerRunEntity run) {
        return hasCurrentMetricsVersion(run)
                && isValidPercentile(run.getParsePercentage())
                && isValidPercentile(run.getKeyParsePercentage())
                && run.getDamagePerSecond() != null;
    }

    private static boolean isValidPercentile(BigDecimal percentile) {
        return percentile != null
                && percentile.signum() > 0
                && percentile.compareTo(MAX_PERCENTILE) <= 0;
    }

    private record RunIdentity(long profileId, String reportCode, int fightId) {
    }

    private record UploadedFightIdentity(
            long profileId,
            String characterName,
            Instant reportStartedAt,
            int fightId
    ) {
    }

    private record DiscoveredReport(
            String code,
            int revision,
            Instant startedAt,
            int actorId,
            Map<Integer, ReportActor> actors
    ) {
    }

    private record ReportFingerprint(Instant startedAt) {
    }

    private record DailyItemLevelKey(long profileId, LocalDate observedDate) {
    }

    private record DailyItemLevelCandidate(
            TrackedPlayer player,
            String reportCode,
            int fightId,
            int actorId,
            Instant observedAt,
            LocalDate observedDate,
            BigDecimal itemLevel
    ) {
    }

    private record FightEvents(
            List<JsonNode> interrupts,
            List<JsonNode> deaths,
            List<JsonNode> damageTaken
    ) {
    }

    private record Participant(
            TrackedPlayer player,
            int fightId,
            int actorId,
            String dungeonName,
            int keystoneLevel,
            long keystoneTimeMs,
            boolean timed,
            Instant fightStartedAt,
            Instant fightEndedAt,
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

    private static final class ExistingRunIndex {
        private final Map<RunIdentity, WarcraftLogPlayerRunEntity> byReport = new LinkedHashMap<>();
        private final Map<UploadedFightIdentity, WarcraftLogPlayerRunEntity> byUploadedFight =
                new LinkedHashMap<>();

        private static ExistingRunIndex from(List<WarcraftLogPlayerRunEntity> runs) {
            ExistingRunIndex index = new ExistingRunIndex();
            runs.forEach(index::remember);
            return index;
        }

        private WarcraftLogPlayerRunEntity find(
                TrackedPlayer player,
                DiscoveredReport report,
                int fightId
        ) {
            WarcraftLogPlayerRunEntity exact = byReport.get(
                    new RunIdentity(player.profileId(), report.code(), fightId)
            );
            if (exact != null) {
                return exact;
            }
            return byUploadedFight.get(new UploadedFightIdentity(
                    player.profileId(), normalizeCharacter(player.name()), report.startedAt(), fightId
            ));
        }

        private void remember(WarcraftLogPlayerRunEntity run) {
            byReport.put(
                    new RunIdentity(run.getProfileId(), run.getReportCode(), run.getFightId()),
                    run
            );
            byUploadedFight.putIfAbsent(new UploadedFightIdentity(
                    run.getProfileId(),
                    normalizeCharacter(run.getCharacterName()),
                    run.getReportStartedAt(),
                    run.getFightId()
            ), run);
        }

        private static String normalizeCharacter(String characterName) {
            return characterName.toLowerCase(Locale.ROOT);
        }
    }
}
