package com.blackbox.wow.service;

import com.blackbox.wow.helper.MPlusAdvancedMetrics;
import com.blackbox.wow.helper.MPlusAdvancedMetrics.Consistency;
import com.blackbox.wow.helper.MPlusAdvancedMetrics.DungeonProfile;
import com.blackbox.wow.properties.MPlusAdvancedProperties;
import com.blackbox.wow.repository.MPlusPerformanceRepository;
import com.blackbox.wow.repository.MPlusPerformanceRepository.ObservedRun;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusProgressRepository.ScorePoint;
import com.blackbox.wow.service.MPlusPlayerResolver.Resolution;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

@Service
public class MPlusAdvancedService {

    private final MPlusPlayerResolver playerResolver;
    private final MPlusProgressRepository progressRepository;
    private final MPlusPerformanceRepository performanceRepository;
    private final MPlusAdvancedProperties properties;

    public MPlusAdvancedService(
            MPlusPlayerResolver playerResolver,
            MPlusProgressRepository progressRepository,
            MPlusPerformanceRepository performanceRepository,
            MPlusAdvancedProperties properties
    ) {
        this.playerResolver = playerResolver;
        this.progressRepository = progressRepository;
        this.performanceRepository = performanceRepository;
        this.properties = properties;
    }

    public String consistencyMessage(String argument, Long telegramUserId) {
        PlayerRuns data = loadPlayerRuns(argument, telegramUserId);
        if (data.error() != null) {
            return data.error();
        }
        if (data.runs().size() < properties.minimumIndividualRuns()) {
            return insufficientRuns(data.player().profileName(), data.season(), data.runs().size());
        }
        Consistency consistency = MPlusAdvancedMetrics.consistency(
                data.runs(), properties.comfortCoveragePercent()
        );
        DungeonProfile dungeon = MPlusAdvancedMetrics.dungeonProfile(data.runs());
        int clutchRuns = MPlusAdvancedMetrics.clutchCount(data.runs(), properties.clutchWindowSeconds());
        return formatConsistency(data, consistency, dungeon, clutchRuns);
    }

    private PlayerRuns loadPlayerRuns(String argument, Long telegramUserId) {
        Resolution resolution = playerResolver.resolveSelfOrNamed(argument, telegramUserId);
        if (resolution.error() != null) {
            return PlayerRuns.error(resolution.error());
        }
        TrackedPlayer player = resolution.player();
        Optional<ScorePoint> latest = progressRepository.latestScore(player.profileId());
        if (latest.isEmpty()) {
            return PlayerRuns.error("Advanced M+ metrics for " + player.profileName()
                    + " are unavailable until its first collection completes.");
        }
        String season = latest.get().season();
        List<ObservedRun> runs = validRuns(player.profileId(), season);
        return PlayerRuns.found(player, season, runs);
    }

    private List<ObservedRun> validRuns(long profileId, String season) {
        return performanceRepository.observedRuns(profileId, season).stream()
                .filter(MPlusAdvancedService::hasValidDuration)
                .toList();
    }

    private String formatConsistency(
            PlayerRuns data,
            Consistency consistency,
            DungeonProfile dungeon,
            int clutchRuns
    ) {
        var comfort = consistency.comfortRange();
        String specialist = dungeon.concentrationPercent() >= properties.specialistMinimumPercent()
                ? "Specialist concentration: " + dungeon.mostPlayedDungeon() + " — "
                    + formatPercent(dungeon.concentrationPercent()) + "% (" + dungeon.mostPlayedRuns() + " runs)"
                : "Specialist concentration: none above " + properties.specialistMinimumPercent() + "%";
        return "Advanced observed M+ metrics — " + data.player().profileName() + " — " + data.season() + '\n'
                + "Sample: N=" + data.runs().size() + " deduplicated observed runs\n"
                + "Consistency (population SD of key levels): " + formatNumber(consistency.standardDeviation()) + '\n'
                + "IQR (Q3 − Q1): " + formatNumber(consistency.interquartileRange())
                + " (Q1=" + formatNumber(consistency.firstQuartile())
                + ", Q3=" + formatNumber(consistency.thirdQuartile()) + ")\n"
                + "Comfort range: +" + comfort.minimumLevel() + " to +" + comfort.maximumLevel()
                + " (smallest interval containing " + comfort.includedRuns() + '/' + comfort.totalRuns()
                + " runs; target " + properties.comfortCoveragePercent() + "%)\n"
                + specialist + '\n'
                + "Dungeon coverage: " + dungeon.uniqueDungeons() + " unique dungeons\n"
                + "Clutch runs: " + clutchRuns + " timed with 1–" + properties.clutchWindowSeconds()
                + " seconds remaining\n"
                + "Observed data only; Raider.IO may not expose every completed run.";
    }

    private String insufficientRuns(String profileName, String season, int sampleSize) {
        return "Advanced M+ metrics for " + profileName + " — " + season + " are unavailable (N="
                + sampleSize + ", need " + properties.minimumIndividualRuns()
                + " deduplicated observed runs).";
    }

    private static boolean hasValidDuration(ObservedRun run) {
        return run.clearTimeMs() > 0 && run.parTimeMs() > 0;
    }

    private static String formatNumber(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String formatPercent(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private record PlayerRuns(TrackedPlayer player, String season, List<ObservedRun> runs, String error) {
        private static PlayerRuns found(TrackedPlayer player, String season, List<ObservedRun> runs) {
            return new PlayerRuns(player, season, runs, null);
        }

        private static PlayerRuns error(String error) {
            return new PlayerRuns(null, null, List.of(), error);
        }
    }

}
