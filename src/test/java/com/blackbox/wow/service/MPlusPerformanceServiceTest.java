package com.blackbox.wow.service;

import com.blackbox.wow.properties.MPlusPerformanceProperties;
import com.blackbox.wow.repository.MPlusPerformanceRepository;
import com.blackbox.wow.repository.MPlusPerformanceRepository.ObservedRun;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusProgressRepository.ScorePoint;
import com.blackbox.wow.service.MPlusPlayerResolver.Resolution;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MPlusPerformanceServiceTest {

    @Mock private MPlusPlayerResolver playerResolver;
    @Mock private MPlusProgressRepository progressRepository;
    @Mock private MPlusPerformanceRepository performanceRepository;

    @Test
    void calculatesKeyAndTimingMetricsForValidObservedRuns() {
        preparePlayer(List.of(
                run(1, "AD", 10, 1_800_000, 2_000_000, true),
                run(2, "AD", 12, 1_950_000, 2_000_000, true),
                run(3, "BD", 9, 2_100_000, 2_000_000, false),
                run(4, "BD", 11, 2_300_000, 2_000_000, false)
        ));

        String message = service(2).performanceMessage("", 123L);

        assertThat(message)
                .contains("Based on 4 observed runs")
                .contains("Average key: +10.5")
                .contains("Median key: +10.5")
                .contains("Average time remaining (timed): 2m 5s (N=2)")
                .contains("Average overtime (depleted): 3m 20s (N=2)")
                .contains("Deaths and clean-run coverage: unavailable");
    }

    @Test
    void hidesAveragesBelowTheConfiguredSampleSize() {
        preparePlayer(List.of(
                run(1, "AD", 10, 1_800_000, 2_000_000, true),
                run(2, "BD", 11, 2_100_000, 2_000_000, false)
        ));

        String message = service(5).performanceMessage("", 123L);

        assertThat(message)
                .contains("Key averages: unavailable (N=2, need 5)")
                .contains("Average time remaining: unavailable (N=1, need 5)")
                .contains("Average overtime: unavailable (N=1, need 5)");
    }

    @Test
    void showsHighestClutchAndFastestRunsWithoutCallingRunScoreAScoreGain() {
        preparePlayer(List.of(
                run(1, "AD", 10, 1_800_000, 2_000_000, true),
                run(2, "AD", 12, 1_950_000, 2_000_000, true),
                run(3, "BD", 11, 1_700_000, 2_000_000, true)
        ));

        String message = service(2).highlightsMessage("", 123L);

        assertThat(message)
                .contains("Highest key: +12 AD (timed)")
                .contains("Closest timed run: +12 AD with 50s remaining")
                .contains("AD — +10 in 30m 0s")
                .contains("BD — +11 in 28m 20s")
                .contains("Largest profile-score gain from one run: unavailable");
    }

    private void preparePlayer(List<ObservedRun> runs) {
        TrackedPlayer player = new TrackedPlayer(1, "Buco", "eu", "Stormscale", "Bucothered");
        when(playerResolver.resolveSelfOrNamed("", 123L)).thenReturn(Resolution.found(player));
        when(progressRepository.latestScore(1)).thenReturn(Optional.of(new ScorePoint(
                1,
                "season-mn-2",
                new BigDecimal("2000"),
                Instant.parse("2026-08-19T10:00:00Z"),
                "eu",
                "Stormscale",
                "Bucothered"
        )));
        when(performanceRepository.observedRuns(1, "season-mn-2")).thenReturn(runs);
    }

    private MPlusPerformanceService service(int minimumSampleSize) {
        return new MPlusPerformanceService(
                playerResolver,
                progressRepository,
                performanceRepository,
                new MPlusPerformanceProperties(minimumSampleSize)
        );
    }

    private static ObservedRun run(
            long runId,
            String dungeon,
            int level,
            long clearTime,
            long parTime,
            boolean timed
    ) {
        return new ObservedRun(
                runId,
                dungeon + " Dungeon",
                dungeon,
                level,
                Instant.parse("2026-08-19T10:00:00Z").minusSeconds(runId * 60),
                clearTime,
                parTime,
                timed ? 1 : 0,
                new BigDecimal("300"),
                timed
        );
    }
}
