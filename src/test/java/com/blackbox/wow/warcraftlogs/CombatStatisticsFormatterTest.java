package com.blackbox.wow.warcraftlogs;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CombatStatisticsFormatterTest {
    @Test
    void formatsAndOrdersCombatStatistics() {
        var alpha = stats("Alpha", "AlphaMain", 2, 1, 1, "2.5", "1", "70", "125000");
        var bravo = stats("Bravo", "BravoMain", 3, 0, 3, "4", "0", "90", "1500000");
        String message = CombatStatisticsFormatter.combatMessage(List.of(alpha, bravo), "", 13, "season-2");
        assertThat(message).containsSubsequence("• Bravo (BravoMain)", "DPS: 1.5m", "• Alpha (AlphaMain)")
                .contains("Duplicate uploads excluded: 1", "Key-parse runs: 1",
                        "Duplicate uploads are identified by Warcraft Logs fight time and duration.");
    }

    @Test
    void distinguishesMissingProfilesFromProfilesWithoutRuns() {
        var empty = stats("Linq", "Linqq", 0, 0, 0, null, null, null, null);
        assertThat(CombatStatisticsFormatter.combatMessage(List.of(empty), "Unknown", 13, "season"))
                .isEqualTo("Active player profile not found: Unknown");
        assertThat(CombatStatisticsFormatter.combatMessage(List.of(empty), " Linq ", 13, "season"))
                .isEqualTo("No logged timed +13 or higher Warcraft Logs combat runs are available for Linq.");
    }

    @Test
    void formatsAwardTiesAndUnavailableMetrics() {
        var alpha = stats("Alpha", "AlphaMain", 2, 0, 0, "4", "1", null, "100000");
        var bravo = stats("Bravo", "BravoMain", 1, 0, 0, "4", "2", null, "200000");
        assertThat(CombatStatisticsFormatter.awardsMessage(List.of(alpha, bravo), 13, "season"))
                .contains("🛑 CC Machine — Most interrupts per run", "• Alpha (AlphaMain)",
                        "• Bravo (BravoMain)", "🔥 Top Pumper — Best average key parse\n• Unavailable");
    }

    private static WarcraftLogsStatisticsService.PlayerStatistics stats(
            String profile, String character, int runs, int duplicates, int parseRuns,
            String interrupts, String deaths, String parse, String dps) {
        return new WarcraftLogsStatisticsService.PlayerStatistics(profile, character, runs, duplicates, parseRuns,
                decimal(interrupts), decimal(deaths), decimal(parse), decimal(dps), null, null);
    }

    private static BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }
}
