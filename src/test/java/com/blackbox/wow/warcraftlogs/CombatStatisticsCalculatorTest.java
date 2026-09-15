package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.entity.WarcraftLogPlayerRunEntity;
import com.blackbox.wow.entity.WarcraftLogProfileSnapshotEntity;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CombatStatisticsCalculatorTest {
    @Test
    void calculatesOnlySelectedMainTimedRunsAndProjectsSnapshotError() {
        TrackedPlayer player = new TrackedPlayer(1, "Linq", "eu", "Stormscale", "Thelinq");
        WarcraftLogProfileSnapshotEntity snapshot = new WarcraftLogProfileSnapshotEntity("season", 1, "Thelinq");
        snapshot.update("Thelinq", null, "private report");
        var result = CombatStatisticsCalculator.calculate(List.of(player), List.of(
                run("Linqq", "old", 20, 4, "99", "300000"),
                run("Thelinq", "ranked", 4, 0, "80", "200000"),
                run("Thelinq", "pending", 2, 2, "0", "180000")
        ), List.of(snapshot)).getFirst();

        assertThat(result.dungeonRuns()).isEqualTo(2);
        assertThat(result.keyParsedDungeonRuns()).isEqualTo(1);
        assertThat(result.averageInterrupts()).isEqualByComparingTo("3");
        assertThat(result.averageDeaths()).isEqualByComparingTo("1");
        assertThat(result.averageKeyParsePercentage()).isEqualByComparingTo("80");
        assertThat(result.averageDamagePerSecond()).isEqualByComparingTo("190000");
        assertThat(result.error()).isEqualTo("private report");
    }

    @Test
    void excludesDuplicateUploadsAndKeepsTheNewerEquivalentUpload() {
        TrackedPlayer player = new TrackedPlayer(1, "Linq", "eu", "Stormscale", "Linqq");
        WarcraftLogPlayerRunEntity original = run("Linqq", "original", 30, 10, "80", "200000");
        original.recordFightWindow(Instant.parse("2026-08-31T10:05:00Z"), Instant.parse("2026-08-31T10:35:00Z"));
        WarcraftLogPlayerRunEntity newer = run("Linqq", "newer", 4, 0, "90", "250000");
        newer.update(2, "Linqq", "King's Rest", 13, 4, 0, null,
                new BigDecimal("90"), new BigDecimal("250000"));
        newer.recordFightWindow(Instant.parse("2026-08-31T10:06:00Z"), Instant.parse("2026-08-31T10:36:00Z"));

        var result = CombatStatisticsCalculator.calculate(
                List.of(player), List.of(original, newer), List.of()).getFirst();
        assertThat(result.dungeonRuns()).isEqualTo(1);
        assertThat(result.duplicateUploads()).isEqualTo(1);
        assertThat(result.averageInterrupts()).isEqualByComparingTo("4");
        assertThat(result.averageKeyParsePercentage()).isEqualByComparingTo("90");
    }

    private static WarcraftLogPlayerRunEntity run(String character, String report, int interrupts,
                                                    int deaths, String keyParse, String dps) {
        WarcraftLogPlayerRunEntity run = new WarcraftLogPlayerRunEntity(
                "season", 1, character, report, 1, Instant.parse("2026-08-19T10:00:00Z"),
                7, "King's Rest", 13, interrupts, deaths, null,
                new BigDecimal(keyParse), new BigDecimal(dps));
        run.recordCompletion(1_800_000, true);
        return run;
    }
}
