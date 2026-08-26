package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.entity.WarcraftLogPlayerRunEntity;
import com.blackbox.wow.repository.WarcraftLogPlayerRunRepository;
import com.blackbox.wow.repository.WarcraftLogProfileSnapshotRepository;
import com.blackbox.wow.service.MPlusRunCorrelationService;
import com.blackbox.wow.service.TrackedPlayerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        WarcraftLogPlayerRunEntity partialFight = run(
                "Thelinq", "partial-fight", 20, 3, null, "250000.0"
        );
        when(runRepository.findBySeasonKey("midnight-season-2"))
                .thenReturn(List.of(previousMainRun, selectedMainRun, partialFight));
        when(snapshotRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Linq", "eu", "Stormscale", "Thelinq")
        ));
        WarcraftLogsStatisticsService service = service(
                trackedPlayerService, runRepository, snapshotRepository
        );

        assertThat(service.combatMessage("Linq"))
                .contains("""
                        • Linq (Thelinq)
                          Key parse: 62%
                          DPS: 140,000
                          Interrupts per run: 4
                          Deaths per run: 0
                          Logged runs: 1
                        """.strip())
                .doesNotContain("N=", "Key-parse runs:")
                .doesNotContain("Linqq", "interrupts 13", "deaths 1");
    }

    @Test
    void ordersCombatProfilesByKeyParseDescending() {
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        WarcraftLogPlayerRunEntity lowerParse = run(
                1L, "AlphaMain", "alpha-report", 2, 1, "42", "100000"
        );
        WarcraftLogPlayerRunEntity higherParse = run(
                2L, "BravoMain", "bravo-report", 3, 0, "88", "150000"
        );
        when(runRepository.findBySeasonKey("midnight-season-2"))
                .thenReturn(List.of(lowerParse, higherParse));
        when(snapshotRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1L, "Alpha", "eu", "Stormscale", "AlphaMain"),
                new TrackedPlayerService.TrackedPlayer(2L, "Bravo", "eu", "Draenor", "BravoMain")
        ));
        WarcraftLogsStatisticsService service = service(
                trackedPlayerService, runRepository, snapshotRepository
        );

        String message = service.combatMessage("");

        assertThat(message.indexOf("• Bravo (BravoMain)")).isLessThan(message.indexOf("• Alpha (AlphaMain)"));
    }

    @Test
    void formatsReadableCombatAwardsFromLoggedRuns() {
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        when(runRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of(
                run(1L, "BucoMain", "buco", 2, 4, "70", "100000"),
                run(2L, "LinqMain", "linq", 12, 1, "80", "120000"),
                run(3L, "LazoMain", "lazo", 4, 0, "95", "180000")
        ));
        when(snapshotRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1L, "Buco", "eu", "Stormscale", "BucoMain"),
                new TrackedPlayerService.TrackedPlayer(2L, "Linq", "eu", "Draenor", "LinqMain"),
                new TrackedPlayerService.TrackedPlayer(3L, "Lazo", "eu", "Tarren Mill", "LazoMain")
        ));

        String message = service(trackedPlayerService, runRepository, snapshotRepository).awardsMessage();

        assertThat(message)
                .contains("💀 Floor POV — Most deaths per run", "• Buco (BucoMain)", "Deaths/run: 4")
                .contains("🛑 CC Machine — Most interrupts per run", "• Linq (LinqMain)", "Interrupts/run: 12")
                .contains("🔥 Top Pumper — Best average key parse", "• Lazo (LazoMain)", "Key parse: 95%")
                .doesNotContain("formula:", "N=");
    }

    @Test
    void formatsRaidCombatAveragesForEveryDifficulty() throws Exception {
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        JsonNode response = new ObjectMapper().readTree("""
                {"characterData":{"character":{"name":"Bucothered",
                  "normal":{"bestPerformanceAverage":81.2},
                  "heroic":{"bestPerformanceAverage":72.5},
                  "mythic":null
                }}}
                """);
        when(client.query(anyString(), anyMap())).thenReturn(response);
        WarcraftLogsStatisticsService service = new WarcraftLogsStatisticsService(
                client,
                properties(),
                trackedPlayerService,
                mock(WarcraftLogPlayerRunRepository.class),
                mock(WarcraftLogProfileSnapshotRepository.class),
                mock(MPlusRunCorrelationService.class),
                mock(WarcraftLogsEventPager.class)
        );

        String message = service.raidCombatMessage(List.of(
                new TrackedPlayerService.TrackedPlayer(1L, "Buco", "eu", "Stormscale", "Bucothered")
        ));

        assertThat(message)
                .contains("Raid Combat — best performance average")
                .contains("Normal: 81.2%", "Heroic: 72.5%", "Mythic: —");
    }

    @Test
    void acceptsOnlyCompletedKeystoneFights() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode completed = mapper.readTree("""
                {"keystoneTime": 1800000, "friendlyPlayers": [42]}
                """);
        JsonNode partial = mapper.readTree("""
                {"keystoneTime": null, "friendlyPlayers": [42]}
                """);

        assertThat(WarcraftLogsStatisticsService.isEligibleFight(completed, 3, 9, 42)).isTrue();
        assertThat(WarcraftLogsStatisticsService.isEligibleFight(partial, 1, 9, 42)).isFalse();
    }

    @Test
    void collectsDuplicateUploadsOnlyOnce() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        WarcraftLogsEventPager eventPager = mock(WarcraftLogsEventPager.class);
        JsonNode reports = mapper.readTree("""
                {"characterData":{"character":{"recentReports":{"data":[
                  {"code":"first","revision":1,"startTime":1000,"endTime":5000,
                   "fights":[{"id":1,"name":"Ruby Life Pools","keystoneLevel":9,
                     "keystoneTime":3000,"startTime":0,"endTime":3000,"friendlyPlayers":[42]}],
                   "masterData":{"actors":[{"id":42,"name":"Thelinq","server":"Stormscale"}]}},
                  {"code":"duplicate","revision":1,"startTime":1000,"endTime":5000,
                   "fights":[{"id":1,"name":"Ruby Life Pools","keystoneLevel":9,
                     "keystoneTime":3000,"startTime":0,"endTime":3000,"friendlyPlayers":[42]}],
                   "masterData":{"actors":[{"id":42,"name":"Thelinq","server":"Stormscale"}]}}
                ]}}}}
                """);
        JsonNode emptyReportData = mapper.readTree("""
                {"reportData":{"report":{}}}
                """);
        when(client.rateLimit()).thenReturn(new WarcraftLogsClient.RateLimit(1000, 0, 3600));
        when(client.query(anyString(), anyMap())).thenReturn(reports, emptyReportData);
        when(runRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of());
        when(runRepository.save(any(WarcraftLogPlayerRunEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.findBySeasonKeyAndProfileId("midnight-season-2", 1))
                .thenReturn(Optional.empty());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Linq", "eu", "Stormscale", "Thelinq")
        ));
        WarcraftLogsStatisticsService service = new WarcraftLogsStatisticsService(
                client,
                properties(),
                trackedPlayerService,
                runRepository,
                snapshotRepository,
                mock(MPlusRunCorrelationService.class),
                eventPager
        );

        service.refresh();

        verify(runRepository, times(1)).save(any(WarcraftLogPlayerRunEntity.class));
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

        assertThat(statistics.dungeonRuns()).isZero();
        assertThat(statistics.averageInterrupts()).isNull();
        assertThat(statistics.averageDeaths()).isNull();
        assertThat(statistics.keyParsedDungeonRuns()).isZero();
        assertThat(statistics.averageKeyParsePercentage()).isNull();
        assertThat(statistics.averageDamagePerSecond()).isNull();
        assertThat(service.combatMessage(""))
                .contains("Key parse: unavailable")
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
        return run(1L, characterName, reportCode, interrupts, deaths, keyParse, damagePerSecond);
    }

    private static WarcraftLogPlayerRunEntity run(
            long profileId,
            String characterName,
            String reportCode,
            int interrupts,
            int deaths,
            String keyParse,
            String damagePerSecond
    ) {
        WarcraftLogPlayerRunEntity run = new WarcraftLogPlayerRunEntity(
                "midnight-season-2", profileId, characterName, reportCode, 1,
                Instant.parse("2026-08-19T10:00:00Z"), 7, "King's Rest", 10,
                interrupts, deaths, null,
                keyParse == null ? null : new BigDecimal(keyParse),
                new BigDecimal(damagePerSecond)
        );
        if (keyParse != null) {
            run.recordCompletion(1_800_000);
        }
        return run;
    }

    private static WarcraftLogsProperties properties() {
        return new WarcraftLogsProperties(
                "", "", "", "", 10, true, "midnight-season-2", Instant.EPOCH, 80
        );
    }
}
