package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.entity.WarcraftLogPlayerRunEntity;
import com.blackbox.wow.entity.WarcraftLogProfileSnapshotEntity;
import com.blackbox.wow.repository.WarcraftLogPlayerRunRepository;
import com.blackbox.wow.repository.WarcraftLogProfileSnapshotRepository;
import com.blackbox.wow.repository.WarcraftLogItemLevelRepository;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WarcraftLogsStatisticsService {

    private static final int RAID_PARSE_DECIMAL_PLACES = 2;
    private static final int MAX_REPORT_PAGES = 100;
    private static final BigDecimal COMPACT_THOUSAND_THRESHOLD = BigDecimal.valueOf(10_000);
    private static final BigDecimal COMPACT_MILLION_THRESHOLD = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1_000);
    private static final String COMPACT_NUMBER_PATTERN = "0.#";
    private static final String STANDARD_NUMBER_PATTERN = "#,##0.##";
    private static final String REFRESH_IN_PROGRESS_MESSAGE =
            "⛏️ Work, work! A peon is refreshing Warcraft Logs data. "
                    + "Please wait and try again shortly.";
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
    private static final String TARGET_ID_FIELD = "targetID";
    private static final String IS_AVOIDABLE_FIELD = "isAvoidable";
    private static final String ABILITY_GAME_ID_FIELD = "abilityGameID";
    private static final String AMOUNT_FIELD = "amount";
    private static final String UNAVAILABLE_LABEL = "unavailable";
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
    private static final String RAID_RANKINGS_QUERY = """
            query CharacterRaidRankings($name: String!, $server: String!, $region: String!) {
              characterData {
                character(name: $name, serverSlug: $server, serverRegion: $region) {
                  name
                  normal: zoneRankings(difficulty: 3)
                  heroic: zoneRankings(difficulty: 4)
                  mythic: zoneRankings(difficulty: 5)
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

    public WarcraftLogsStatisticsService(
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
            List<WarcraftLogPlayerRunEntity> runs = runsByProfile.getOrDefault(player.profileId(), List.of())
                    .stream()
                    .filter(run -> run.getCharacterName().equalsIgnoreCase(player.name()))
                    .toList();
            List<WarcraftLogPlayerRunEntity> completeRuns = runs.stream()
                    .filter(WarcraftLogsStatisticsService::hasCompleteCombatMetrics)
                    .toList();
            int interrupts = completeRuns.stream().mapToInt(WarcraftLogPlayerRunEntity::getInterrupts).sum();
            int deaths = completeRuns.stream().mapToInt(WarcraftLogPlayerRunEntity::getDeaths).sum();
            List<BigDecimal> keyParses = nonNullMetrics(
                    completeRuns, WarcraftLogPlayerRunEntity::getKeyParsePercentage
            );
            List<BigDecimal> damagePerSecond = nonNullMetrics(
                    completeRuns, WarcraftLogPlayerRunEntity::getDamagePerSecond
            );
            List<BigDecimal> avoidableDamage = nonNullMetrics(
                    completeRuns, WarcraftLogPlayerRunEntity::getAvoidableDamage
            );
            WarcraftLogProfileSnapshotEntity snapshot = snapshotsByProfile.get(player.profileId());
            result.add(new PlayerStatistics(
                    player.profileName(),
                    player.name(),
                    completeRuns.size(),
                    keyParses.size(),
                    average(interrupts, completeRuns.size()),
                    average(deaths, completeRuns.size()),
                    average(keyParses),
                    average(damagePerSecond),
                    average(avoidableDamage),
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
        if (refreshRunning.get()) {
            return REFRESH_IN_PROGRESS_MESSAGE;
        }
        String requestedProfile = profileArgument == null ? "" : profileArgument.trim();
        List<PlayerStatistics> selected = statistics().stream()
                .filter(statistic -> requestedProfile.isBlank()
                        || statistic.profileName().equalsIgnoreCase(requestedProfile))
                .sorted(combatStatisticComparator())
                .toList();
        if (refreshRunning.get()) {
            return REFRESH_IN_PROGRESS_MESSAGE;
        }
        if (selected.isEmpty()) {
            return requestedProfile.isBlank()
                    ? "No Warcraft Logs combat statistics are available."
                    : "Active player profile not found: " + requestedProfile;
        }
        StringBuilder message = new StringBuilder("Warcraft Logs M+ combat — ")
                .append(properties.seasonKey()).append("\n\n");
        selected.forEach(statistic -> appendCombatStatistic(message, statistic));
        return message.append("Averages use logged runs only; missing/private logs are unavailable, not zero.")
                .toString();
    }

    public String awardsMessage() {
        if (refreshRunning.get()) {
            return REFRESH_IN_PROGRESS_MESSAGE;
        }
        List<PlayerStatistics> candidates = statistics().stream()
                .filter(statistic -> statistic.dungeonRuns() > 0)
                .toList();
        if (refreshRunning.get()) {
            return REFRESH_IN_PROGRESS_MESSAGE;
        }
        if (candidates.isEmpty()) {
            return "M+ awards are unavailable until Warcraft Logs combat data is collected.";
        }

        StringBuilder message = new StringBuilder("🏆 M+ Awards — ")
                .append(properties.seasonKey()).append("\n")
                .append("Based on logged dungeon runs; ties are shown.\n\n");
        appendCombatAward(
                message,
                "💀 Floor POV",
                "Most deaths per run",
                "Deaths/run",
                candidates,
                PlayerStatistics::averageDeaths,
                WarcraftLogsStatisticsService::formatMetric
        );
        appendCombatAward(
                message,
                "🛑 CC Machine",
                "Most interrupts per run",
                "Interrupts/run",
                candidates,
                PlayerStatistics::averageInterrupts,
                WarcraftLogsStatisticsService::formatMetric
        );
        appendCombatAward(
                message,
                "🔥 Top Pumper",
                "Best average key parse",
                "Key parse",
                candidates,
                PlayerStatistics::averageKeyParsePercentage,
                WarcraftLogsStatisticsService::formatPercentMetric
        );
        appendCombatAward(
                message,
                "🔥 Stand in Fire DPS higher",
                "Most average avoidable damage taken",
                "Avoidable damage/run",
                candidates,
                PlayerStatistics::averageAvoidableDamage,
                WarcraftLogsStatisticsService::formatDamageAmount
        );
        return message.append("Logged runs only; missing or private logs are excluded.").toString();
    }

    public String raidCombatMessage(List<TrackedPlayer> players) {
        if (players.isEmpty()) {
            return "No active profiles are available for raid combat parses.";
        }
        StringBuilder message = new StringBuilder("Raid Combat — best performance average\n\n");
        for (TrackedPlayer player : players) {
            appendRaidCombatProfile(message, player);
        }
        return message.append("Public Warcraft Logs rankings; unavailable difficulties are shown as —.")
                .toString();
    }

    private void appendRaidCombatProfile(StringBuilder message, TrackedPlayer player) {
        try {
            JsonNode character = client.query(RAID_RANKINGS_QUERY, Map.of(
                    NAME_FIELD, player.name(),
                    SERVER_FIELD, player.realm().toLowerCase(Locale.ROOT),
                    REGION_FIELD, player.region().toUpperCase(Locale.ROOT)
            )).path(CHARACTER_DATA_FIELD).path(CHARACTER_FIELD);
            if (character.isMissingNode() || character.isNull()) {
                throw new IllegalStateException("character rankings unavailable");
            }
            message.append("• ").append(player.profileName()).append(" (")
                    .append(character.path(NAME_FIELD).asText(player.name())).append(")\n")
                    .append("  Normal: ").append(formatRaidParse(character.path("normal"))).append('\n')
                    .append("  Heroic: ").append(formatRaidParse(character.path("heroic"))).append('\n')
                    .append("  Mythic: ").append(formatRaidParse(character.path("mythic"))).append("\n\n");
        } catch (RuntimeException _) {
            message.append("• ").append(player.profileName()).append(" (").append(player.name())
                    .append("): unavailable\n\n");
        }
    }

    private static String formatRaidParse(JsonNode rankings) {
        JsonNode average = rankings.path("bestPerformanceAverage");
        if (!average.isNumber()) {
            return "—";
        }
        BigDecimal roundedAverage = average.decimalValue()
                .setScale(RAID_PARSE_DECIMAL_PLACES, RoundingMode.HALF_UP);
        return formatPercentMetric(roundedAverage);
    }

    private static void appendCombatAward(
            StringBuilder message,
            String title,
            String description,
            String metricLabel,
            List<PlayerStatistics> candidates,
            Function<PlayerStatistics, BigDecimal> metric,
            Function<BigDecimal, String> formatter
    ) {
        List<PlayerStatistics> eligible = candidates.stream()
                .filter(candidate -> metric.apply(candidate) != null)
                .toList();
        message.append(title).append(" — ").append(description).append('\n');
        if (eligible.isEmpty()) {
            message.append("• Unavailable\n\n");
            return;
        }

        BigDecimal winningValue = eligible.stream()
                .map(metric)
                .max(BigDecimal::compareTo)
                .orElseThrow();
        eligible.stream()
                .filter(candidate -> metric.apply(candidate).compareTo(winningValue) == 0)
                .forEach(candidate -> appendCombatAwardWinner(
                        message,
                        candidate,
                        metricLabel,
                        winningValue,
                        formatter
                ));
        message.append('\n');
    }

    private static void appendCombatAwardWinner(
            StringBuilder message,
            PlayerStatistics winner,
            String metricLabel,
            BigDecimal metric,
            Function<BigDecimal, String> formatter
    ) {
        message.append("• ").append(winner.profileName()).append(" (")
                .append(winner.characterName()).append(")\n  ")
                .append(metricLabel).append(": ").append(formatter.apply(metric));
        message.append("\n  Logged runs: ").append(winner.dungeonRuns()).append('\n');
    }

    private static Comparator<PlayerStatistics> combatStatisticComparator() {
        return Comparator
                .comparing(
                        PlayerStatistics::averageKeyParsePercentage,
                        Comparator.nullsLast(Comparator.reverseOrder())
                )
                .thenComparing(PlayerStatistics::profileName, String.CASE_INSENSITIVE_ORDER);
    }

    private static void appendCombatStatistic(StringBuilder message, PlayerStatistics statistic) {
        message.append("• ").append(statistic.profileName()).append(" (")
                .append(statistic.characterName()).append(")\n")
                .append("  Key parse: ").append(formatPercentMetric(statistic.averageKeyParsePercentage())).append('\n')
                .append("  DPS: ").append(formatDamagePerSecond(statistic.averageDamagePerSecond())).append('\n')
                .append("  Avoidable damage per run: ")
                .append(formatDamageAmount(statistic.averageAvoidableDamage())).append('\n')
                .append("  Interrupts per run: ").append(formatMetric(statistic.averageInterrupts())).append('\n')
                .append("  Deaths per run: ").append(formatMetric(statistic.averageDeaths())).append('\n')
                .append("  Logged runs: ").append(statistic.dungeonRuns()).append('\n');
        if (statistic.keyParsedDungeonRuns() != statistic.dungeonRuns()) {
            message.append("  Key-parse runs: ").append(statistic.keyParsedDungeonRuns()).append('\n');
        }
        message.append('\n');
    }

    private static String formatMetric(BigDecimal value) {
        return value == null ? UNAVAILABLE_LABEL : value.stripTrailingZeros().toPlainString();
    }

    private static String formatPercentMetric(BigDecimal value) {
        return value == null ? UNAVAILABLE_LABEL : formatMetric(value) + '%';
    }

    private static String formatDamagePerSecond(BigDecimal value) {
        return formatDamageAmount(value);
    }

    private static String formatDamageAmount(BigDecimal value) {
        if (value == null) return UNAVAILABLE_LABEL;
        if (value.compareTo(COMPACT_MILLION_THRESHOLD) >= 0) {
            return formatNumber(value.divide(COMPACT_MILLION_THRESHOLD), COMPACT_NUMBER_PATTERN) + 'm';
        }
        if (value.compareTo(COMPACT_THOUSAND_THRESHOLD) >= 0) {
            return formatNumber(value.divide(ONE_THOUSAND), COMPACT_NUMBER_PATTERN) + 'k';
        }
        return formatNumber(value, STANDARD_NUMBER_PATTERN);
    }

    private static String formatNumber(BigDecimal value, String pattern) {
        DecimalFormat formatter = new DecimalFormat(
                pattern, DecimalFormatSymbols.getInstance(Locale.US)
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
                report.participants.forEach(participant ->
                        saveSnapshot(participant.player, null, "report " + report.code + " could not be read"));
                log.warn("Could not load Warcraft Logs report {}: {}", report.code, e.getMessage());
            }
        }
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
                .filter(run -> !hasCurrentMetricsVersion(run))
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
                log.warn("Could not backfill Warcraft Logs report {}: {}", entry.getKey(), exception.getMessage());
            }
        }
        return savedRuns;
    }

    private ReportWork storedReportWork(
            String reportCode,
            List<WarcraftLogPlayerRunEntity> storedRuns,
            Map<Long, TrackedPlayer> playersById
    ) {
        JsonNode reportData = client.query(STORED_REPORT_QUERY, Map.of(REPORT_CODE_FIELD, reportCode))
                .path(REPORT_DATA_FIELD)
                .path(REPORT_FIELD);
        if (reportData.isMissingNode() || reportData.isNull()) {
            throw new IllegalStateException("stored report is unavailable");
        }
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
            if (actorId == null || run.getKeystoneTimeMs() == null || run.getKeystoneTimeMs() <= 0) {
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
                    run.getKeystoneTimeMs(),
                    run
            ));
        }
        return report;
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
            candidates.merge(key, candidate, WarcraftLogsStatisticsService::earlierCandidate);
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
        if (!isEligibleFight(fight, fightId, keyLevel, discoveredReport.actorId())) {
            return;
        }

        correlateFight(fight, player, discoveredReport, fightId, keyLevel);

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
                existing
        ));
    }

    static boolean isEligibleFight(JsonNode fight, int fightId, int keyLevel, int actorId) {
        return fightId > 0
                && keyLevel > 0
                && fight.path("keystoneTime").asLong(0) > 0
                && containsInt(fight.path("friendlyPlayers"), actorId);
    }

    private static boolean isCurrentRun(WarcraftLogPlayerRunEntity existing, int revision) {
        return existing != null && existing.getReportRevision() >= revision && hasCompleteMetrics(existing);
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
                fight.path(NAME_FIELD).asText(""), keyLevel,
                report.startedAt().plusMillis(startOffset), report.startedAt().plusMillis(endOffset),
                endOffset - startOffset, roster
        ));
    }

    private int loadAndSaveReportEvents(
            ReportWork report,
            ExistingRunIndex existingRuns
    ) {
        if (report.fightIds.isEmpty()) return 0;

        JsonNode reportData = client.query(
                        rankingsQuery(report.fightIds), Map.of(REPORT_CODE_FIELD, report.code)
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
            entity.recordAvoidableDamage(avoidableDamage);
            entity.recordCompletion(participant.keystoneTimeMs);
            entity = runRepository.save(entity);
            existingRuns.remember(entity);
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
            String name = actor.path(NAME_FIELD).asText("");
            if (id > 0 && !name.isBlank()) {
                result.put(id, new ReportActor(id, name, actor.path(SERVER_FIELD).asText("")));
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

    static BigDecimal findFriendlyItemLevel(JsonNode fight, int actorId) {
        JsonNode friendlyPlayers = fight.path("friendlyPlayers");
        JsonNode friendlyItemLevels = fight.path("friendlyItemLevels");
        if (!friendlyPlayers.isArray() || !friendlyItemLevels.isArray()
                || friendlyPlayers.size() != friendlyItemLevels.size()) {
            return null;
        }
        for (int index = 0; index < friendlyPlayers.size(); index++) {
            JsonNode itemLevel = friendlyItemLevels.path(index);
            if (friendlyPlayers.path(index).asInt(-1) == actorId
                    && itemLevel.isNumber()
                    && itemLevel.decimalValue().signum() > 0) {
                return itemLevel.decimalValue();
            }
        }
        return null;
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
            if (event.path(TARGET_ID_FIELD).asInt(-1) == actorId
                    || (!event.has(TARGET_ID_FIELD) && event.path(SOURCE_ID_FIELD).asInt(-1) == actorId)) {
                count++;
            }
        }
        return count;
    }

    static BigDecimal sumAvoidableDamageForActor(
            Iterable<JsonNode> events,
            int actorId,
            String dungeonName
    ) {
        BigDecimal total = BigDecimal.ZERO;
        Optional<Set<Long>> catalogue = MidnightSeason2AvoidableAbilities.abilitiesFor(dungeonName);
        Set<Long> avoidableAbilityIds = catalogue.orElseGet(Set::of);
        boolean classificationAvailable = catalogue.isPresent();
        for (JsonNode event : events) {
            if (event.path(TARGET_ID_FIELD).asInt(-1) != actorId) {
                continue;
            }
            JsonNode explicitClassification = event.path(IS_AVOIDABLE_FIELD);
            classificationAvailable |= explicitClassification.isBoolean();
            JsonNode amount = event.path(AMOUNT_FIELD);
            if (isAvoidableDamageEvent(event, avoidableAbilityIds, explicitClassification)
                    && amount.isNumber()
                    && amount.decimalValue().signum() >= 0) {
                total = total.add(amount.decimalValue());
            }
        }
        return classificationAvailable ? total : null;
    }

    private static boolean isAvoidableDamageEvent(
            JsonNode event,
            Set<Long> avoidableAbilityIds,
            JsonNode explicitClassification
    ) {
        if (explicitClassification.isBoolean()) {
            return explicitClassification.asBoolean();
        }
        JsonNode abilityGameId = event.path(ABILITY_GAME_ID_FIELD);
        return abilityGameId.canConvertToLong()
                && avoidableAbilityIds.contains(abilityGameId.asLong());
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
                || ranking.path(SOURCE_ID_FIELD).asInt(-1) == actorId) {
            return true;
        }
        return ranking.path(NAME_FIELD).asText("").equalsIgnoreCase(characterName);
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
            Function<WarcraftLogPlayerRunEntity, BigDecimal> metric
    ) {
        return runs.stream().map(metric).filter(Objects::nonNull).toList();
    }

    private static boolean hasCurrentMetricsVersion(WarcraftLogPlayerRunEntity run) {
        return run.getMetricsVersion() >= WarcraftLogPlayerRunEntity.CURRENT_METRICS_VERSION;
    }

    private static boolean hasCompleteMetrics(WarcraftLogPlayerRunEntity run) {
        return hasCurrentMetricsVersion(run)
                && run.getKeyParsePercentage() != null
                && run.getDamagePerSecond() != null;
    }

    private static boolean hasCompleteCombatMetrics(WarcraftLogPlayerRunEntity run) {
        return hasCurrentMetricsVersion(run)
                && run.getKeystoneTimeMs() != null
                && run.getKeystoneTimeMs() > 0;
    }

    public record PlayerStatistics(
            String profileName,
            String characterName,
            int dungeonRuns,
            int keyParsedDungeonRuns,
            BigDecimal averageInterrupts,
            BigDecimal averageDeaths,
            BigDecimal averageKeyParsePercentage,
            BigDecimal averageDamagePerSecond,
            BigDecimal averageAvoidableDamage,
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

    private record ReportActor(int id, String name, String server) {
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
