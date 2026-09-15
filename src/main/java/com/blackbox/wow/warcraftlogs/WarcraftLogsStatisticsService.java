package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.entity.WarcraftLogPlayerRunEntity;
import com.blackbox.wow.repository.WarcraftLogPlayerRunRepository;
import com.blackbox.wow.repository.WarcraftLogProfileSnapshotRepository;
import com.blackbox.wow.service.CombatKeyLevelService;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
public class WarcraftLogsStatisticsService {
    private static final String REFRESH_IN_PROGRESS_MESSAGE =
            "⛏️ Work, work! A peon is refreshing Warcraft Logs data. "
                    + "Please wait and try again shortly.";

    private final WarcraftLogsProperties properties;
    private final TrackedPlayerService trackedPlayerService;
    private final WarcraftLogPlayerRunRepository runRepository;
    private final WarcraftLogProfileSnapshotRepository snapshotRepository;
    private final CombatKeyLevelService combatKeyLevelService;
    private final WarcraftLogsRaidStatisticsService raidStatisticsService;
    private final WarcraftLogsCollectionService collectionService;

    public WarcraftLogsStatisticsService(
            WarcraftLogsProperties properties,
            TrackedPlayerService trackedPlayerService,
            WarcraftLogPlayerRunRepository runRepository,
            WarcraftLogProfileSnapshotRepository snapshotRepository,
            CombatKeyLevelService combatKeyLevelService,
            WarcraftLogsRaidStatisticsService raidStatisticsService,
            WarcraftLogsCollectionService collectionService
    ) {
        this.properties = properties;
        this.trackedPlayerService = trackedPlayerService;
        this.runRepository = runRepository;
        this.snapshotRepository = snapshotRepository;
        this.combatKeyLevelService = combatKeyLevelService;
        this.raidStatisticsService = raidStatisticsService;
        this.collectionService = collectionService;
    }

    public List<PlayerStatistics> statistics() { return statistics(0); }

    private List<PlayerStatistics> statistics(int minimumKeystoneLevel) {
        String seasonKey = properties.seasonKey();
        List<WarcraftLogPlayerRunEntity> runs = minimumKeystoneLevel > 0
                ? runRepository.findTimedBySeasonKeyAndMinimumKeystoneLevel(seasonKey, minimumKeystoneLevel)
                : runRepository.findBySeasonKey(seasonKey);
        return CombatStatisticsCalculator.calculate(
                trackedPlayerService.activePlayers(), runs, snapshotRepository.findBySeasonKey(seasonKey));
    }

    public String seasonKey() { return properties.seasonKey(); }

    public Instant seasonStart() { return properties.seasonStart(); }

    public String combatMessage(String profileArgument) {
        if (collectionService.isRefreshRunning()) return REFRESH_IN_PROGRESS_MESSAGE;
        int minimumLevel = combatKeyLevelService.currentLevel();
        List<PlayerStatistics> statistics = statistics(minimumLevel);
        if (collectionService.isRefreshRunning()) return REFRESH_IN_PROGRESS_MESSAGE;
        return CombatStatisticsFormatter.combatMessage(
                statistics, profileArgument, minimumLevel, properties.seasonKey());
    }

    public String awardsMessage() {
        if (collectionService.isRefreshRunning()) return REFRESH_IN_PROGRESS_MESSAGE;
        int minimumLevel = combatKeyLevelService.currentLevel();
        List<PlayerStatistics> statistics = statistics(minimumLevel);
        if (collectionService.isRefreshRunning()) return REFRESH_IN_PROGRESS_MESSAGE;
        return CombatStatisticsFormatter.awardsMessage(statistics, minimumLevel, properties.seasonKey());
    }

    public String raidCombatMessage(List<TrackedPlayer> players) {
        return raidStatisticsService.raidCombatMessage(players);
    }

    @Scheduled(cron = "${warcraft-logs.refresh-cron:0 0,30 * * * *}",
            zone = "${warcraft-logs.refresh-zone:Europe/Zagreb}")
    public void refreshScheduled() { refresh(); }

    public void refresh() { collectionService.refresh(); }

    public record PlayerStatistics(
            String profileName,
            String characterName,
            int dungeonRuns,
            int duplicateUploads,
            int keyParsedDungeonRuns,
            BigDecimal averageInterrupts,
            BigDecimal averageDeaths,
            BigDecimal averageKeyParsePercentage,
            BigDecimal averageDamagePerSecond,
            BigDecimal averageAvoidableDamage,
            String error
    ) {}
}
