package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.warcraftlogs.WarcraftLogsStatisticsService.PlayerStatistics;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

final class CombatStatisticsFormatter {
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000);
    private static final BigDecimal COMPACT_THOUSAND = BigDecimal.valueOf(10_000);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final String DUPLICATES =
            " Duplicate uploads are identified by Warcraft Logs fight time and duration.";
    private static final List<Award> AWARDS = List.of(
            new Award("💀 Floor POV", "Most deaths per run", "🪽 Not Today, Spirit Healer",
                    "Fewest deaths per run", "Deaths/run", PlayerStatistics::averageDeaths,
                    CombatStatisticsFormatter::metric),
            new Award("🛑 CC Machine", "Most interrupts per run", "💿 My Kick Was on Cooldown",
                    "Fewest interrupts per run", "Interrupts/run", PlayerStatistics::averageInterrupts,
                    CombatStatisticsFormatter::metric),
            new Award("🔥 Top Pumper", "Best average key parse", "🎮 Are You Pressing Buttons?",
                    "Lowest average key parse", "Key parse", PlayerStatistics::averageKeyParsePercentage,
                    CombatStatisticsFormatter::percent)
    );

    private CombatStatisticsFormatter() {}

    static String combatMessage(List<PlayerStatistics> statistics, String profileArgument,
                                int minimumLevel, String seasonKey) {
        String requested = profileArgument == null ? "" : profileArgument.trim();
        boolean exists = requested.isBlank() || statistics.stream()
                .anyMatch(value -> value.profileName().equalsIgnoreCase(requested));
        List<PlayerStatistics> selected = statistics.stream()
                .filter(value -> requested.isBlank() || value.profileName().equalsIgnoreCase(requested))
                .filter(value -> value.dungeonRuns() > 0)
                .sorted(Comparator.comparing(PlayerStatistics::averageKeyParsePercentage,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PlayerStatistics::profileName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (selected.isEmpty()) {
            if (!exists) return "Active player profile not found: " + requested;
            String suffix = requested.isBlank() ? "." : " for " + requested + ".";
            return "No logged timed +" + minimumLevel
                    + " or higher Warcraft Logs combat runs are available" + suffix;
        }
        StringBuilder message = new StringBuilder("Warcraft Logs M+ combat (timed +")
                .append(minimumLevel).append(" and above) — ").append(seasonKey).append("\n\n");
        selected.forEach(value -> appendStatistic(message, value));
        return message.append("Averages use distinct logged timed +").append(minimumLevel)
                .append(" or higher runs; depleted, missing, and private logs are excluded.")
                .append(DUPLICATES).toString();
    }

    static String awardsMessage(List<PlayerStatistics> statistics, int minimumLevel, String seasonKey) {
        List<PlayerStatistics> candidates = statistics.stream().filter(value -> value.dungeonRuns() > 0).toList();
        if (candidates.isEmpty()) return "M+ awards are unavailable until Warcraft Logs combat data is collected.";
        StringBuilder message = new StringBuilder("🏆 M+ Awards — timed +").append(minimumLevel)
                .append(" and above — ").append(seasonKey)
                .append("\nBased on distinct logged timed runs; ties are shown.\n\n");
        appendAwards(message, candidates, Direction.MAXIMUM);
        appendAwards(message, candidates, Direction.MINIMUM);
        return message.append("Timed +").append(minimumLevel)
                .append(" or higher runs only; depleted, missing, and private logs are excluded.")
                .append(DUPLICATES).toString();
    }

    private static void appendStatistic(StringBuilder out, PlayerStatistics value) {
        out.append("• ").append(value.profileName()).append(" (").append(value.characterName()).append(")\n")
                .append("  Key parse: ").append(percent(value.averageKeyParsePercentage())).append('\n')
                .append("  DPS: ").append(dps(value.averageDamagePerSecond())).append('\n')
                .append("  Interrupts per run: ").append(metric(value.averageInterrupts())).append('\n')
                .append("  Deaths per run: ").append(metric(value.averageDeaths())).append('\n')
                .append("  Logged runs: ").append(value.dungeonRuns()).append('\n');
        appendDuplicates(out, value);
        if (value.keyParsedDungeonRuns() != value.dungeonRuns())
            out.append("  Key-parse runs: ").append(value.keyParsedDungeonRuns()).append('\n');
        out.append('\n');
    }

    private static void appendAwards(StringBuilder out, List<PlayerStatistics> candidates, Direction direction) {
        out.append(direction.heading).append("\n\n");
        AWARDS.forEach(award -> appendAward(out, candidates, award, direction));
    }

    private static void appendAward(StringBuilder out, List<PlayerStatistics> candidates,
                                    Award award, Direction direction) {
        List<PlayerStatistics> eligible = candidates.stream()
                .filter(value -> award.value.apply(value) != null).toList();
        out.append(direction.title(award)).append(" — ").append(direction.description(award)).append('\n');
        if (eligible.isEmpty()) {
            out.append("• Unavailable\n\n");
            return;
        }
        BigDecimal winner = direction.select(eligible.stream().map(award.value));
        eligible.stream().filter(value -> award.value.apply(value).compareTo(winner) == 0)
                .forEach(value -> {
                    out.append("• ").append(value.profileName()).append(" (").append(value.characterName())
                            .append(")\n  ").append(award.label).append(": ").append(award.format.apply(winner))
                            .append("\n  Logged runs: ").append(value.dungeonRuns()).append('\n');
                    appendDuplicates(out, value);
                });
        out.append('\n');
    }

    private static void appendDuplicates(StringBuilder out, PlayerStatistics value) {
        if (value.duplicateUploads() > 0)
            out.append("  Duplicate uploads excluded: ").append(value.duplicateUploads()).append('\n');
    }

    private static String metric(BigDecimal value) {
        return value == null ? "unavailable" : value.stripTrailingZeros().toPlainString();
    }

    static String percent(BigDecimal value) { return value == null ? "unavailable" : metric(value) + '%'; }

    private static String dps(BigDecimal value) {
        if (value == null) return "unavailable";
        if (value.compareTo(MILLION) >= 0) return number(value.divide(MILLION), "0.#") + 'm';
        if (value.compareTo(COMPACT_THOUSAND) >= 0) return number(value.divide(THOUSAND), "0.#") + 'k';
        return number(value, "#,##0.##");
    }

    private static String number(BigDecimal value, String pattern) {
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US)).format(value);
    }

    private record Award(String maxTitle, String maxDescription, String minTitle, String minDescription,
                         String label, Function<PlayerStatistics, BigDecimal> value,
                         Function<BigDecimal, String> format) {}

    private enum Direction {
        MAXIMUM("⬆️ Maximum awards") {
            BigDecimal select(Stream<BigDecimal> values) { return values.max(BigDecimal::compareTo).orElseThrow(); }
            String title(Award value) { return value.maxTitle; }
            String description(Award value) { return value.maxDescription; }
        },
        MINIMUM("⬇️ Minimum awards") {
            BigDecimal select(Stream<BigDecimal> values) { return values.min(BigDecimal::compareTo).orElseThrow(); }
            String title(Award value) { return value.minTitle; }
            String description(Award value) { return value.minDescription; }
        };
        private final String heading;
        Direction(String heading) { this.heading = heading; }
        abstract BigDecimal select(Stream<BigDecimal> values);
        abstract String title(Award value);
        abstract String description(Award value);
    }
}
