package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.entity.WarcraftLogPlayerRunEntity;
import com.blackbox.wow.repository.WarcraftLogPlayerRunRepository;
import com.blackbox.wow.repository.WarcraftLogProfileSnapshotRepository;
import com.blackbox.wow.service.MPlusRunCorrelationService;
import com.blackbox.wow.service.TrackedPlayerService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WarcraftLogsStatisticsServiceTest {

    @Test
    void doesNotMixLegacyPercentilesWithUnavailableCurrentDps() {
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        WarcraftLogPlayerRunEntity incompleteRun = new WarcraftLogPlayerRunEntity(
                "midnight-season-2", 1, "Linqq", "report", 1,
                Instant.parse("2026-08-19T10:00:00Z"), 7, "King's Rest", 10,
                9, 1, new BigDecimal("87"), new BigDecimal("100"), null
        );
        when(runRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of(incompleteRun));
        when(snapshotRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Linq", "eu", "Stormscale", "Linqq")
        ));
        WarcraftLogsStatisticsService service = new WarcraftLogsStatisticsService(
                mock(WarcraftLogsClient.class),
                properties(),
                trackedPlayerService,
                runRepository,
                snapshotRepository,
                mock(MPlusRunCorrelationService.class),
                mock(WarcraftLogsEventPager.class)
        );

        WarcraftLogsStatisticsService.PlayerStatistics statistics = service.statistics().getFirst();

        assertThat(statistics.averageInterrupts()).isEqualByComparingTo("9");
        assertThat(statistics.averageDeaths()).isEqualByComparingTo("1");
        assertThat(statistics.rankedDungeonRuns()).isZero();
        assertThat(statistics.averageParsePercentage()).isNull();
        assertThat(statistics.averageKeyParsePercentage()).isNull();
        assertThat(statistics.averageDamagePerSecond()).isNull();
    }

    private static WarcraftLogsProperties properties() {
        return new WarcraftLogsProperties(
                "", "", "", "", 10, true, "midnight-season-2", Instant.EPOCH, 80
        );
    }
}
