package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.entity.WarcraftLogPlayerRunEntity;
import com.blackbox.wow.entity.WarcraftLogProfileSnapshotEntity;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

final class CombatStatisticsCalculator {
    private static final Duration DUPLICATE_FIGHT_TIME_TOLERANCE = Duration.ofMinutes(3);
    private static final long DUPLICATE_KEYSTONE_TIME_TOLERANCE_MS = 30_000;
    private static final BigDecimal MAX_PERCENTILE = BigDecimal.valueOf(100);

    private CombatStatisticsCalculator() {}

    static List<WarcraftLogsStatisticsService.PlayerStatistics> calculate(
            List<TrackedPlayer> players,
            List<WarcraftLogPlayerRunEntity> seasonRuns,
            List<WarcraftLogProfileSnapshotEntity> snapshots
    ) {
        Map<Long, List<WarcraftLogPlayerRunEntity>> runsByProfile = seasonRuns.stream()
                .collect(Collectors.groupingBy(WarcraftLogPlayerRunEntity::getProfileId));
        Map<Long, WarcraftLogProfileSnapshotEntity> snapshotsByProfile = snapshots.stream()
                .collect(Collectors.toMap(WarcraftLogProfileSnapshotEntity::getProfileId, value -> value));
        List<WarcraftLogsStatisticsService.PlayerStatistics> result = new ArrayList<>();
        for (TrackedPlayer player : players) {
            List<WarcraftLogPlayerRunEntity> eligible = runsByProfile
                    .getOrDefault(player.profileId(), List.of()).stream()
                    .filter(run -> run.getCharacterName().equalsIgnoreCase(player.name()))
                    .filter(CombatStatisticsCalculator::hasCompleteCombatMetrics).toList();
            List<WarcraftLogPlayerRunEntity> runs = distinctDungeonRuns(eligible);
            List<BigDecimal> keyParses = metrics(runs, WarcraftLogPlayerRunEntity::getKeyParsePercentage)
                    .stream().filter(CombatStatisticsCalculator::validPercentile).toList();
            WarcraftLogProfileSnapshotEntity snapshot = snapshotsByProfile.get(player.profileId());
            result.add(new WarcraftLogsStatisticsService.PlayerStatistics(
                    player.profileName(), player.name(), runs.size(), eligible.size() - runs.size(), keyParses.size(),
                    average(runs.stream().mapToInt(WarcraftLogPlayerRunEntity::getInterrupts).sum(), runs.size()),
                    average(runs.stream().mapToInt(WarcraftLogPlayerRunEntity::getDeaths).sum(), runs.size()),
                    average(keyParses), average(metrics(runs, WarcraftLogPlayerRunEntity::getDamagePerSecond)),
                    average(metrics(runs, WarcraftLogPlayerRunEntity::getAvoidableDamage)),
                    snapshot == null ? null : snapshot.getLastError()
            ));
        }
        return List.copyOf(result);
    }

    private static BigDecimal average(int total, int count) {
        return count == 0 ? null : BigDecimal.valueOf(total)
                .divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal average(List<BigDecimal> values) {
        return values.isEmpty() ? null : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 1, RoundingMode.HALF_UP);
    }

    private static List<BigDecimal> metrics(List<WarcraftLogPlayerRunEntity> runs,
                                             Function<WarcraftLogPlayerRunEntity, BigDecimal> metric) {
        return runs.stream().map(metric).filter(Objects::nonNull).toList();
    }

    static List<WarcraftLogPlayerRunEntity> distinctDungeonRuns(List<WarcraftLogPlayerRunEntity> uploads) {
        List<WarcraftLogPlayerRunEntity> ordered = uploads.stream().sorted(Comparator
                .comparing(WarcraftLogPlayerRunEntity::getFightEndedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WarcraftLogPlayerRunEntity::getReportStartedAt)
                .thenComparing(WarcraftLogPlayerRunEntity::getReportCode)
                .thenComparingInt(WarcraftLogPlayerRunEntity::getFightId)).toList();
        List<WarcraftLogPlayerRunEntity> distinct = new ArrayList<>();
        for (WarcraftLogPlayerRunEntity candidate : ordered) {
            int index = duplicateIndex(distinct, candidate);
            if (index < 0) distinct.add(candidate);
            else if (preferred(candidate, distinct.get(index))) distinct.set(index, candidate);
        }
        return List.copyOf(distinct);
    }

    private static int duplicateIndex(List<WarcraftLogPlayerRunEntity> runs,
                                      WarcraftLogPlayerRunEntity candidate) {
        for (int i = 0; i < runs.size(); i++) if (duplicate(runs.get(i), candidate)) return i;
        return -1;
    }

    private static boolean duplicate(WarcraftLogPlayerRunEntity first, WarcraftLogPlayerRunEntity second) {
        if (first.getFightEndedAt() == null || second.getFightEndedAt() == null
                || first.getKeystoneTimeMs() == null || second.getKeystoneTimeMs() == null) return false;
        return first.getProfileId().equals(second.getProfileId())
                && first.getCharacterName().equalsIgnoreCase(second.getCharacterName())
                && first.getKeystoneLevel() == second.getKeystoneLevel()
                && normalize(first.getDungeonName()).equals(normalize(second.getDungeonName()))
                && Duration.between(first.getFightEndedAt(), second.getFightEndedAt()).abs()
                        .compareTo(DUPLICATE_FIGHT_TIME_TOLERANCE) <= 0
                && Math.abs(first.getKeystoneTimeMs() - second.getKeystoneTimeMs())
                        <= DUPLICATE_KEYSTONE_TIME_TOLERANCE_MS;
    }

    private static boolean preferred(WarcraftLogPlayerRunEntity candidate, WarcraftLogPlayerRunEntity existing) {
        int comparison = Integer.compare(quality(candidate), quality(existing));
        return comparison != 0 ? comparison > 0 : candidate.getReportRevision() > existing.getReportRevision();
    }

    private static int quality(WarcraftLogPlayerRunEntity run) {
        int quality = validPercentile(run.getKeyParsePercentage()) ? 4 : 0;
        quality += validPercentile(run.getParsePercentage()) ? 2 : 0;
        return run.getDamagePerSecond() == null ? quality : quality + 1;
    }

    private static boolean hasCompleteCombatMetrics(WarcraftLogPlayerRunEntity run) {
        return run.getMetricsVersion() >= WarcraftLogPlayerRunEntity.CURRENT_METRICS_VERSION
                && run.getKeystoneTimeMs() != null && run.getKeystoneTimeMs() > 0
                && Boolean.TRUE.equals(run.getTimed());
    }

    private static boolean validPercentile(BigDecimal value) {
        return value != null && value.signum() > 0 && value.compareTo(MAX_PERCENTILE) <= 0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }
}
