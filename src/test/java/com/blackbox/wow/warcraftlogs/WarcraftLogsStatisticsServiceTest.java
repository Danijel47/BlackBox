package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.entity.WarcraftLogPlayerRunEntity;
import com.blackbox.wow.repository.WarcraftLogPlayerRunRepository;
import com.blackbox.wow.repository.WarcraftLogProfileSnapshotRepository;
import com.blackbox.wow.service.MPlusRunCorrelationService;
import com.blackbox.wow.service.TrackedPlayerService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WarcraftLogsStatisticsServiceTest {

    @Test
    void reportsOnlyRunsForTheCurrentlySelectedMain() {
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        WarcraftLogPlayerRunEntity previousMainRun = run(
                "Linqq", "old-report", 9, 1, "49", "124013.3"
        );
        WarcraftLogPlayerRunEntity selectedMainRun = run(
                "Thelinq", "new-report", 4, 0, "62", "140000.0"
        );
        when(runRepository.findBySeasonKey("midnight-season-2"))
                .thenReturn(List.of(previousMainRun, selectedMainRun));
        when(snapshotRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Linq", "eu", "Stormscale", "Thelinq")
        ));
        WarcraftLogsStatisticsService service = service(
                trackedPlayerService, runRepository, snapshotRepository
        );

        assertThat(service.combatMessage("Linq"))
                .contains("Linq (Thelinq) — N=1, interrupts 4, deaths 0, Key parse 62%, DPS 140,000")
                .doesNotContain("Linqq", "interrupts 13", "deaths 1");
    }

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
        ReflectionTestUtils.setField(incompleteRun, "metricsVersion", 4);
        when(runRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of(incompleteRun));
        when(snapshotRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Linq", "eu", "Stormscale", "Linqq")
        ));
        WarcraftLogsStatisticsService service = service(
                trackedPlayerService, runRepository, snapshotRepository
        );

        WarcraftLogsStatisticsService.PlayerStatistics statistics = service.statistics().getFirst();

        assertThat(statistics.averageInterrupts()).isEqualByComparingTo("9");
        assertThat(statistics.averageDeaths()).isEqualByComparingTo("1");
        assertThat(statistics.keyParsedDungeonRuns()).isZero();
        assertThat(statistics.averageKeyParsePercentage()).isNull();
        assertThat(statistics.averageDamagePerSecond()).isNull();
        assertThat(service.combatMessage(""))
                .contains("Key parse unavailable")
                .doesNotContain(", Parse ", ", Key unavailable");
    }

    private static WarcraftLogsStatisticsService service(
            TrackedPlayerService trackedPlayerService,
            WarcraftLogPlayerRunRepository runRepository,
            WarcraftLogProfileSnapshotRepository snapshotRepository
    ) {
        return new WarcraftLogsStatisticsService(
                mock(WarcraftLogsClient.class),
                properties(),
                trackedPlayerService,
                runRepository,
                snapshotRepository,
                mock(MPlusRunCorrelationService.class),
                mock(WarcraftLogsEventPager.class)
        );
    }

    private static WarcraftLogPlayerRunEntity run(
            String characterName,
            String reportCode,
            int interrupts,
            int deaths,
            String keyParse,
            String damagePerSecond
    ) {
        return new WarcraftLogPlayerRunEntity(
                "midnight-season-2", 1, characterName, reportCode, 1,
                Instant.parse("2026-08-19T10:00:00Z"), 7, "King's Rest", 10,
                interrupts, deaths, null, new BigDecimal(keyParse), new BigDecimal(damagePerSecond)
        );
    }

    private static WarcraftLogsProperties properties() {
        return new WarcraftLogsProperties(
                "", "", "", "", 10, true, "midnight-season-2", Instant.EPOCH, 80
        );
    }
}
