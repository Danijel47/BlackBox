package com.blackbox.wow.service;

import com.blackbox.wow.properties.MPlusPerformanceProperties;
import com.blackbox.wow.repository.MPlusPerformanceRepository;
import com.blackbox.wow.repository.MPlusPerformanceRepository.ObservedRun;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusProgressRepository.ScorePoint;
import com.blackbox.wow.service.MPlusPlayerResolver.Resolution;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class MPlusPerformanceService {

    private final MPlusPlayerResolver playerResolver;
    private final MPlusProgressRepository progressRepository;
    private final MPlusPerformanceRepository performanceRepository;
    private final MPlusPerformanceProperties properties;

    public MPlusPerformanceService(
            MPlusPlayerResolver playerResolver,
            MPlusProgressRepository progressRepository,
            MPlusPerformanceRepository performanceRepository,
            MPlusPerformanceProperties properties
    ) {
        this.playerResolver = playerResolver;
        this.progressRepository = progressRepository;
        this.performanceRepository = performanceRepository;
        this.properties = properties;
    }

    public String performanceMessage(String argument, Long telegramUserId) {
        PerformanceData data = loadPerformanceData(argument, telegramUserId);
        if (data.error() != null) {
            return data.error();
        }
        return formatPerformance(data.player(), data.season(), data.runs());
    }

    public String highlightsMessage(String argument, Long telegramUserId) {
        PerformanceData data = loadPerformanceData(argument, telegramUserId);
        if (data.error() != null) {
            return data.error();
        }
        return formatHighlights(data.player(), data.season(), data.runs());
    }

    private PerformanceData loadPerformanceData(String argument, Long telegramUserId) {
        Resolution resolution = playerResolver.resolveSelfOrNamed(argument, telegramUserId);
        if (resolution.error() != null) {
            return PerformanceData.error(resolution.error());
        }
        TrackedPlayer player = resolution.player();
        Optional<ScorePoint> latest = progressRepository.latestScore(player.profileId());
        if (latest.isEmpty()) {
            return PerformanceData.error("Observed-run performance for " + player.profileName()
                    + " is unavailable until its first collection completes.");
        }
        List<ObservedRun> runs = performanceRepository.observedRuns(
                player.profileId(), latest.get().season()
        ).stream().filter(MPlusPerformanceService::hasValidDuration).toList();
        if (runs.isEmpty()) {
            return PerformanceData.error("No valid observed Mythic+ runs are available for "
                    + player.profileName() + " in " + latest.get().season() + ".");
        }
        return PerformanceData.found(player, latest.get().season(), runs);
    }

    private String formatPerformance(TrackedPlayer player, String season, List<ObservedRun> runs) {
        List<ObservedRun> timedRuns = runs.stream().filter(MPlusPerformanceService::isTimed).toList();
        List<ObservedRun> depletedRuns = runs.stream().filter(MPlusPerformanceService::isDepleted).toList();
        StringBuilder message = new StringBuilder("Observed M+ performance — ")
                .append(player.profileName())
                .append(" — ")
                .append(season)
                .append('\n')
                .append("Based on ")
                .append(runs.size())
                .append(" observed runs.\n");
        if (runs.size() >= properties.minimumSampleSize()) {
            message.append("Average key: +").append(formatDecimal(averageKey(runs))).append('\n')
                    .append("Median key: +").append(formatDecimal(medianKey(runs))).append('\n');
        } else {
            appendInsufficientSample(message, "Key averages", runs.size());
        }
        appendDurationAverage(message, "Average time remaining", timedRuns, true);
        appendDurationAverage(message, "Average overtime", depletedRuns, false);
        return message.append("Deaths and clean-run coverage: unavailable until a run is matched to Warcraft Logs.\n")
                .append("Use /mplus_combat for the existing log-only aggregates.")
                .toString();
    }

    private String formatHighlights(TrackedPlayer player, String season, List<ObservedRun> runs) {
        ObservedRun highest = runs.stream().max(Comparator
                .comparingInt(ObservedRun::mythicLevel)
                .thenComparing(ObservedRun::timed))
                .orElseThrow();
        Optional<ObservedRun> clutch = runs.stream()
                .filter(MPlusPerformanceService::isTimed)
                .min(Comparator.comparingLong(ObservedRun::timeRemainingMs));
        StringBuilder message = new StringBuilder("Observed M+ highlights — ")
                .append(player.profileName())
                .append(" — ")
                .append(season)
                .append('\n')
                .append("Based on ")
                .append(runs.size())
                .append(" observed runs.\n")
                .append("Highest key: +")
                .append(highest.mythicLevel())
                .append(' ')
                .append(highest.shortName())
                .append(highest.timed() ? " (timed)\n" : " (depleted)\n");
        if (clutch.isPresent()) {
            message.append("Closest timed run: +")
                    .append(clutch.get().mythicLevel())
                    .append(' ')
                    .append(clutch.get().shortName())
                    .append(" with ")
                    .append(formatDuration(clutch.get().timeRemainingMs()))
                    .append(" remaining\n");
        } else {
            message.append("Closest timed run: unavailable\n");
        }
        message.append("Fastest observed by dungeon:\n");
        for (ObservedRun fastest : fastestByDungeon(runs)) {
            message.append("• ")
                    .append(fastest.shortName())
                    .append(" — +")
                    .append(fastest.mythicLevel())
                    .append(" in ")
                    .append(formatDuration(fastest.clearTimeMs()))
                    .append('\n');
        }
        return message.append("Largest profile-score gain from one run: unavailable from Raider.IO run score data.")
                .toString();
    }

    private void appendDurationAverage(
            StringBuilder message,
            String label,
            List<ObservedRun> runs,
            boolean remaining
    ) {
        if (runs.size() < properties.minimumSampleSize()) {
            appendInsufficientSample(message, label, runs.size());
            return;
        }
        double average = runs.stream()
                .mapToLong(remaining ? ObservedRun::timeRemainingMs : ObservedRun::overtimeMs)
                .average()
                .orElse(0);
        message.append(label)
                .append(remaining ? " (timed): " : " (depleted): ")
                .append(formatDuration(Math.round(average)))
                .append(" (N=")
                .append(runs.size())
                .append(")\n");
    }

    private void appendInsufficientSample(StringBuilder message, String label, int sampleSize) {
        message.append(label)
                .append(": unavailable (N=")
                .append(sampleSize)
                .append(", need ")
                .append(properties.minimumSampleSize())
                .append(")\n");
    }

    private static List<ObservedRun> fastestByDungeon(List<ObservedRun> runs) {
        Map<String, ObservedRun> fastest = new LinkedHashMap<>();
        List<ObservedRun> ordered = new ArrayList<>(runs);
        ordered.sort(Comparator.comparing(ObservedRun::shortName, String.CASE_INSENSITIVE_ORDER));
        for (ObservedRun run : ordered) {
            String key = run.shortName().toLowerCase(Locale.ROOT);
            fastest.merge(key, run, (left, right) ->
                    left.clearTimeMs() <= right.clearTimeMs() ? left : right);
        }
        return List.copyOf(fastest.values());
    }

    private static BigDecimal averageKey(List<ObservedRun> runs) {
        int total = runs.stream().mapToInt(ObservedRun::mythicLevel).sum();
        return BigDecimal.valueOf(total)
                .divide(BigDecimal.valueOf(runs.size()), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal medianKey(List<ObservedRun> runs) {
        List<Integer> levels = runs.stream().map(ObservedRun::mythicLevel).sorted().toList();
        int middle = levels.size() / 2;
        if (levels.size() % 2 == 1) {
            return BigDecimal.valueOf(levels.get(middle));
        }
        return BigDecimal.valueOf(levels.get(middle - 1) + levels.get(middle))
                .divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP);
    }

    private static boolean hasValidDuration(ObservedRun run) {
        return run.clearTimeMs() > 0 && run.parTimeMs() > 0;
    }

    private static boolean isTimed(ObservedRun run) {
        return run.timed() && run.timeRemainingMs() >= 0;
    }

    private static boolean isDepleted(ObservedRun run) {
        return !run.timed() && run.overtimeMs() > 0;
    }

    private static String formatDecimal(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatDuration(long milliseconds) {
        long totalSeconds = Math.max(0, Math.round(milliseconds / 1_000.0));
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }

    private record PerformanceData(
            TrackedPlayer player,
            String season,
            List<ObservedRun> runs,
            String error
    ) {
        private static PerformanceData found(TrackedPlayer player, String season, List<ObservedRun> runs) {
            return new PerformanceData(player, season, runs, null);
        }

        private static PerformanceData error(String error) {
            return new PerformanceData(null, null, List.of(), error);
        }
    }
}
