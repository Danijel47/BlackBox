package com.blackbox.wow.helper;

import com.blackbox.wow.helper.MPlusAdvancedMetrics.Consistency;
import com.blackbox.wow.helper.MPlusAdvancedMetrics.DungeonProfile;
import com.blackbox.wow.repository.MPlusPerformanceRepository.ObservedRun;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MPlusAdvancedMetricsTest {

    @Test
    void calculatesPopulationVarianceQuartilesAndShortestComfortRange() {
        List<ObservedRun> runs = List.of(
                run(1, "AD", 8, 100),
                run(2, "AD", 9, 20),
                run(3, "BD", 10, 30),
                run(4, "BD", 11, -1)
        );

        Consistency result = MPlusAdvancedMetrics.consistency(runs, 50);

        assertThat(result.standardDeviation()).isCloseTo(Math.sqrt(1.25), within(0.000_001));
        assertThat(result.firstQuartile()).isEqualTo(8.5);
        assertThat(result.thirdQuartile()).isEqualTo(10.5);
        assertThat(result.interquartileRange()).isEqualTo(2.0);
        assertThat(result.comfortRange().minimumLevel()).isEqualTo(8);
        assertThat(result.comfortRange().maximumLevel()).isEqualTo(9);
        assertThat(result.comfortRange().includedRuns()).isEqualTo(2);
    }

    @Test
    void calculatesDungeonConcentrationCoverageAndClutchRuns() {
        List<ObservedRun> runs = List.of(
                run(1, "AD", 8, 60),
                run(2, "ad", 9, 61),
                run(3, "BD", 10, 1),
                run(4, "CC", 11, -1)
        );

        DungeonProfile profile = MPlusAdvancedMetrics.dungeonProfile(runs);

        assertThat(profile.mostPlayedDungeon()).isEqualTo("AD");
        assertThat(profile.mostPlayedRuns()).isEqualTo(2);
        assertThat(profile.uniqueDungeons()).isEqualTo(3);
        assertThat(profile.concentrationPercent()).isEqualTo(50.0);
        assertThat(MPlusAdvancedMetrics.clutchCount(runs, 60)).isEqualTo(2);
    }

    private static ObservedRun run(long id, String dungeon, int level, long remainingSeconds) {
        long parTime = 2_000_000;
        long clearTime = parTime - remainingSeconds * 1_000;
        return new ObservedRun(
                id,
                dungeon + " Dungeon",
                dungeon,
                level,
                Instant.parse("2026-08-19T10:00:00Z").minusSeconds(id),
                clearTime,
                parTime,
                remainingSeconds >= 0 ? 1 : 0,
                BigDecimal.TEN,
                remainingSeconds >= 0
        );
    }
}
