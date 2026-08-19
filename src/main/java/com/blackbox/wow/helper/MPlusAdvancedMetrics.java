package com.blackbox.wow.helper;

import com.blackbox.wow.repository.MPlusPerformanceRepository.ObservedRun;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MPlusAdvancedMetrics {

    private MPlusAdvancedMetrics() {
    }

    public static Consistency consistency(List<ObservedRun> runs, int coveragePercent) {
        List<Integer> levels = runs.stream().map(ObservedRun::mythicLevel).sorted().toList();
        double average = levels.stream().mapToInt(Integer::intValue).average().orElse(0);
        double variance = levels.stream()
                .mapToDouble(level -> square(level - average))
                .average()
                .orElse(0);
        double firstQuartile = median(lowerHalf(levels));
        double thirdQuartile = median(upperHalf(levels));
        ComfortRange comfortRange = shortestComfortRange(levels, coveragePercent);
        return new Consistency(
                Math.sqrt(variance),
                firstQuartile,
                thirdQuartile,
                thirdQuartile - firstQuartile,
                comfortRange
        );
    }

    public static DungeonProfile dungeonProfile(List<ObservedRun> runs) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> displayNames = new HashMap<>();
        for (ObservedRun run : runs) {
            String key = run.shortName().toLowerCase(Locale.ROOT);
            counts.merge(key, 1, Integer::sum);
            displayNames.putIfAbsent(key, run.shortName());
        }
        Map.Entry<String, Integer> mostPlayed = counts.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue()
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .orElseThrow();
        double concentration = mostPlayed.getValue() * 100.0 / runs.size();
        return new DungeonProfile(
                displayNames.get(mostPlayed.getKey()),
                mostPlayed.getValue(),
                counts.size(),
                concentration
        );
    }

    public static int clutchCount(List<ObservedRun> runs, int windowSeconds) {
        long maximumRemainingMs = windowSeconds * 1_000L;
        return Math.toIntExact(runs.stream()
                .filter(ObservedRun::timed)
                .mapToLong(ObservedRun::timeRemainingMs)
                .filter(remaining -> remaining > 0 && remaining <= maximumRemainingMs)
                .count());
    }

    private static ComfortRange shortestComfortRange(List<Integer> levels, int coveragePercent) {
        int required = Math.max(1, (int) Math.ceil(levels.size() * coveragePercent / 100.0));
        int bestStart = levels.getFirst();
        int bestEnd = levels.get(required - 1);
        for (int startIndex = 1; startIndex + required <= levels.size(); startIndex++) {
            int candidateStart = levels.get(startIndex);
            int candidateEnd = levels.get(startIndex + required - 1);
            int candidateWidth = candidateEnd - candidateStart;
            int bestWidth = bestEnd - bestStart;
            if (candidateWidth < bestWidth
                    || (candidateWidth == bestWidth && candidateStart < bestStart)) {
                bestStart = candidateStart;
                bestEnd = candidateEnd;
            }
        }
        return new ComfortRange(bestStart, bestEnd, required, levels.size());
    }

    private static List<Integer> lowerHalf(List<Integer> sortedLevels) {
        return new ArrayList<>(sortedLevels.subList(0, sortedLevels.size() / 2));
    }

    private static List<Integer> upperHalf(List<Integer> sortedLevels) {
        int start = (sortedLevels.size() + 1) / 2;
        return new ArrayList<>(sortedLevels.subList(start, sortedLevels.size()));
    }

    private static double median(List<Integer> sortedValues) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int middle = sortedValues.size() / 2;
        if (sortedValues.size() % 2 == 1) {
            return sortedValues.get(middle);
        }
        return (sortedValues.get(middle - 1) + sortedValues.get(middle)) / 2.0;
    }

    private static double square(double value) {
        return value * value;
    }

    public record Consistency(
            double standardDeviation,
            double firstQuartile,
            double thirdQuartile,
            double interquartileRange,
            ComfortRange comfortRange
    ) {
    }

    public record ComfortRange(int minimumLevel, int maximumLevel, int includedRuns, int totalRuns) {
    }

    public record DungeonProfile(
            String mostPlayedDungeon,
            int mostPlayedRuns,
            int uniqueDungeons,
            double concentrationPercent
    ) {
    }
}
