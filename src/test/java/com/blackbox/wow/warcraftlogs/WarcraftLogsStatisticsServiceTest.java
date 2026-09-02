package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.entity.WarcraftLogPlayerRunEntity;
import com.blackbox.wow.repository.WarcraftLogPlayerRunRepository;
import com.blackbox.wow.repository.WarcraftLogProfileSnapshotRepository;
import com.blackbox.wow.repository.WarcraftLogItemLevelRepository;
import com.blackbox.wow.service.MPlusRunCorrelationService;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.warcraftlogs.WarcraftLogsEventPager.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WarcraftLogsStatisticsServiceTest {

    private static final String RUBY_LIFE_POOLS = "Ruby Life Pools";

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
                1L, "Thelinq", "new-report", 4, 0, "62", "140000.0", "9500"
        );
        WarcraftLogPlayerRunEntity partialFight = run(
                "Thelinq", "partial-fight", 20, 3, null, "250000.0"
        );
        when(runRepository.findTimedBySeasonKeyAndMinimumKeystoneLevel("midnight-season-2", 12))
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
                          DPS: 140k
                          Interrupts per run: 4
                          Deaths per run: 0
                          Logged runs: 1
                        """.strip())
                .doesNotContain("N=", "Key-parse runs:")
                .doesNotContain("Avoidable damage")
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
        when(runRepository.findTimedBySeasonKeyAndMinimumKeystoneLevel("midnight-season-2", 12))
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
    void calculatesCombatMetricsFromConfiguredTimedKeysOnly() {
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        when(runRepository.findTimedBySeasonKeyAndMinimumKeystoneLevel("midnight-season-2", 12))
                .thenReturn(List.of(
                        runAtLevel(12, 1L, "Thelinq", "level-twelve", 4, 0, "80", "200000", "4000")
                ));
        when(snapshotRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Linq", "eu", "Stormscale", "Thelinq")
        ));

        String message = service(trackedPlayerService, runRepository, snapshotRepository)
                .combatMessage("Linq");

        assertThat(message)
                .contains("Warcraft Logs M+ combat (timed +12 and above) — midnight-season-2")
                .contains("Key parse: 80%", "DPS: 200k")
                .contains("Interrupts per run: 4", "Deaths per run: 0", "Logged runs: 1")
                .contains("Averages use logged timed +12 or higher runs")
                .contains("depleted, missing, and private logs are excluded")
                .doesNotContain("Avoidable damage", "Key parse: 40%", "Interrupts per run: 20");
        verify(runRepository).findTimedBySeasonKeyAndMinimumKeystoneLevel("midnight-season-2", 12);
    }

    @Test
    void excludesPendingZeroPercentilesWithoutDroppingOtherCombatMetrics() {
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        when(runRepository.findTimedBySeasonKeyAndMinimumKeystoneLevel("midnight-season-2", 12))
                .thenReturn(List.of(
                        runAtLevel(14, 1L, "Bucothered", "ranked", 4, 0, "80", "200000", null),
                        runAtLevel(14, 1L, "Bucothered", "pending", 2, 2, "0", "180000", null)
                ));
        when(snapshotRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Buco", "eu", "Stormscale", "Bucothered")
        ));

        String message = service(trackedPlayerService, runRepository, snapshotRepository)
                .combatMessage("Buco");

        assertThat(message)
                .contains("Key parse: 80%", "DPS: 190k")
                .contains("Interrupts per run: 3", "Deaths per run: 1")
                .contains("Logged runs: 2", "Key-parse runs: 1")
                .doesNotContain("Key parse: 40%");
    }

    @Test
    void formatsReadableCombatAwardsFromLoggedRuns() {
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        when(runRepository.findTimedBySeasonKeyAndMinimumKeystoneLevel("midnight-season-2", 12))
                .thenReturn(List.of(
                run(1L, "BucoMain", "buco", 2, 4, "70", "100000", "2800000"),
                run(2L, "LinqMain", "linq", 12, 1, "80", "120000", "300000"),
                run(3L, "LazoMain", "lazo", 4, 0, "95", "180000", "200000")
                ));
        when(snapshotRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1L, "Buco", "eu", "Stormscale", "BucoMain"),
                new TrackedPlayerService.TrackedPlayer(2L, "Linq", "eu", "Draenor", "LinqMain"),
                new TrackedPlayerService.TrackedPlayer(3L, "Lazo", "eu", "Tarren Mill", "LazoMain")
        ));

        String message = service(trackedPlayerService, runRepository, snapshotRepository).awardsMessage();

        assertThat(message)
                .contains("🏆 M+ Awards — timed +12 and above — midnight-season-2")
                .contains("⬆️ Maximum awards")
                .contains("💀 Floor POV — Most deaths per run", "• Buco (BucoMain)", "Deaths/run: 4")
                .contains("🛑 CC Machine — Most interrupts per run", "• Linq (LinqMain)", "Interrupts/run: 12")
                .contains("🔥 Top Pumper — Best average key parse", "• Lazo (LazoMain)", "Key parse: 95%")
                .contains("⬇️ Minimum awards")
                .contains("🪽 Not Today, Spirit Healer — Fewest deaths per run", "Deaths/run: 0")
                .contains("💿 My Kick Was on Cooldown — Fewest interrupts per run", "Interrupts/run: 2")
                .contains("🎮 Are You Pressing Buttons? — Lowest average key parse", "Key parse: 70%")
                .contains("Timed +12 or higher runs only")
                .contains("depleted, missing, and private logs are excluded")
                .doesNotContain("Avoidable damage", "Stand in Fire", "Fire Bad", "formula:", "N=");
        verify(runRepository).findTimedBySeasonKeyAndMinimumKeystoneLevel("midnight-season-2", 12);
    }

    @Test
    void asksUsersToWaitWhileCombatDataIsRefreshing() {
        WarcraftLogsStatisticsService service = service(
                mock(TrackedPlayerService.class),
                mock(WarcraftLogPlayerRunRepository.class),
                mock(WarcraftLogProfileSnapshotRepository.class)
        );
        AtomicBoolean refreshRunning = (AtomicBoolean) ReflectionTestUtils.getField(
                service, "refreshRunning"
        );
        assertThat(refreshRunning).isNotNull();
        refreshRunning.set(true);
        String expectedMessage = "⛏️ Work, work! A peon is refreshing Warcraft Logs data. "
                + "Please wait and try again shortly.";

        assertThat(service.combatMessage("")).isEqualTo(expectedMessage);
        assertThat(service.awardsMessage()).isEqualTo(expectedMessage);
    }

    @Test
    void sumsOnlyExplicitlyAvoidableDamageTakenByThePlayer() throws Exception {
        JsonNode events = new ObjectMapper().readTree("""
                [
                  {"targetID":42,"isAvoidable":true,"amount":120000.5},
                  {"targetID":42,"isAvoidable":false,"amount":900000},
                  {"targetID":7,"isAvoidable":true,"amount":800000},
                  {"targetID":42,"amount":700000},
                  {"targetID":42,"isAvoidable":true,"amount":30000}
                ]
                """);

        assertThat(WarcraftLogsStatisticsService.sumAvoidableDamageForActor(
                events, 42, RUBY_LIFE_POOLS
        ))
                .isEqualByComparingTo("150000.5");
    }

    @Test
    void classifiesSeasonTwoAvoidableDamageByDungeonAndAbility() throws Exception {
        JsonNode events = new ObjectMapper().readTree("""
                [
                  {"targetID":42,"abilityGameID":373614,"amount":120000},
                  {"targetID":42,"abilityGameID":372735,"amount":900000},
                  {"targetID":7,"abilityGameID":373614,"amount":800000}
                ]
                """);

        assertThat(WarcraftLogsStatisticsService.sumAvoidableDamageForActor(
                events, 42, RUBY_LIFE_POOLS
        )).isEqualByComparingTo("120000");
    }

    @Test
    void recordsZeroWhenSupportedDungeonHasNoAvoidableHits() throws Exception {
        JsonNode events = new ObjectMapper().readTree("""
                [{"targetID":42,"abilityGameID":372735,"amount":120000}]
                """);

        assertThat(WarcraftLogsStatisticsService.sumAvoidableDamageForActor(
                events, 42, RUBY_LIFE_POOLS
        )).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void keepsAvoidableDamageUnavailableForAnUnsupportedDungeon() throws Exception {
        JsonNode events = new ObjectMapper().readTree("""
                [{"targetID":42,"abilityGameID":373614,"amount":120000}]
                """);

        assertThat(WarcraftLogsStatisticsService.sumAvoidableDamageForActor(
                events, 42, "Unknown Dungeon"
        )).isNull();
    }

    @Test
    void explicitWarcraftLogsClassificationOverridesTheCatalogue() throws Exception {
        JsonNode events = new ObjectMapper().readTree("""
                [{"targetID":42,"abilityGameID":373614,"isAvoidable":false,"amount":120000}]
                """);

        assertThat(WarcraftLogsStatisticsService.sumAvoidableDamageForActor(
                events, 42, RUBY_LIFE_POOLS
        )).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void formatsRaidCombatAveragesForEveryDifficulty() throws Exception {
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        JsonNode response = new ObjectMapper().readTree("""
                {"characterData":{"character":{"name":"Bucothered",
                  "normal":{"bestPerformanceAverage":81.68728134929741},
                  "heroic":{"bestPerformanceAverage":24.692101313564862},
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
                mock(WarcraftLogsEventPager.class),
                mock(WarcraftLogItemLevelRepository.class)
        );

        String message = service.raidCombatMessage(List.of(
                new TrackedPlayerService.TrackedPlayer(1L, "Buco", "eu", "Stormscale", "Bucothered")
        ));

        assertThat(message)
                .contains("Raid Combat — best performance average")
                .contains("Normal: 81.69%", "Heroic: 24.69%", "Mythic: —")
                .doesNotContain("81.68728134929741", "24.692101313564862");
    }

    @Test
    void sortsRaidCombatProfilesByBestAvailableParse() throws Exception {
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode buco = mapper.readTree("""
                {"characterData":{"character":{"name":"Bucothered",
                  "normal":{"bestPerformanceAverage":60},"heroic":null,"mythic":null}}}
                """);
        JsonNode linq = mapper.readTree("""
                {"characterData":{"character":{"name":"Thelinq",
                  "normal":{"bestPerformanceAverage":85},"heroic":null,"mythic":null}}}
                """);
        when(client.query(anyString(), anyMap())).thenReturn(buco, linq);
        WarcraftLogsStatisticsService service = new WarcraftLogsStatisticsService(
                client,
                properties(),
                trackedPlayerService,
                mock(WarcraftLogPlayerRunRepository.class),
                mock(WarcraftLogProfileSnapshotRepository.class),
                mock(MPlusRunCorrelationService.class),
                mock(WarcraftLogsEventPager.class),
                mock(WarcraftLogItemLevelRepository.class)
        );

        String report = service.raidCombatMessage(List.of(
                new TrackedPlayerService.TrackedPlayer(1L, "Buco", "eu", "Stormscale", "Bucothered"),
                new TrackedPlayerService.TrackedPlayer(2L, "Linq", "eu", "Draenor", "Thelinq")
        ));

        assertThat(report.indexOf("• Linq")).isLessThan(report.indexOf("• Buco"));
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
    void matchesHistoricalItemLevelToTheFriendlyPlayer() throws Exception {
        JsonNode fight = new ObjectMapper().readTree("""
                {"friendlyPlayers":[7,2,9],"friendlyItemLevels":[305,301,298]}
                """);

        assertThat(WarcraftLogsStatisticsService.findFriendlyItemLevel(fight, 2))
                .isEqualByComparingTo("301");
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
        WarcraftLogItemLevelRepository itemLevelRepository = mock(WarcraftLogItemLevelRepository.class);
        JsonNode reports = mapper.readTree("""
                {"characterData":{"character":{"recentReports":{"data":[
                  {"code":"first","revision":1,"startTime":1000,"endTime":5000,
                   "fights":[{"id":1,"name":"Ruby Life Pools","keystoneLevel":9,
                     "keystoneTime":3000,"keystoneBonus":1,"startTime":0,"endTime":3000,
                     "friendlyPlayers":[42],"friendlyItemLevels":[303]}],
                   "masterData":{"actors":[{"id":42,"name":"Thelinq","server":"Stormscale"}]}},
                  {"code":"duplicate","revision":1,"startTime":1000,"endTime":5000,
                   "fights":[{"id":1,"name":"Ruby Life Pools","keystoneLevel":9,
                     "keystoneTime":3000,"keystoneBonus":1,"startTime":0,"endTime":3000,
                     "friendlyPlayers":[42],"friendlyItemLevels":[303]}],
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
                eventPager,
                itemLevelRepository
        );

        service.refresh();

        verify(runRepository, times(1)).save(any(WarcraftLogPlayerRunEntity.class));
        verify(itemLevelRepository).save(
                1L,
                "Thelinq",
                LocalDate.of(1970, 1, 1),
                Instant.ofEpochSecond(1),
                new BigDecimal("303"),
                "first",
                1
        );
    }

    @Test
    void retriesPendingRankingsOnTheNextRefresh() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        WarcraftLogsEventPager eventPager = mock(WarcraftLogsEventPager.class);
        MPlusRunCorrelationService correlationService = mock(MPlusRunCorrelationService.class);
        WarcraftLogPlayerRunEntity pendingRun = new WarcraftLogPlayerRunEntity(
                "midnight-season-2", 1, "Bucothered", "pending", 1,
                Instant.parse("2026-09-02T20:00:00Z"), 7, "Voidscar Arena", 14,
                2, 0, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("180000")
        );
        pendingRun.recordCompletion(1_500_000, true);
        JsonNode reports = mapper.readTree("""
                {"characterData":{"character":{"recentReports":{"data":[
                  {"code":"pending","revision":1,"startTime":1788379200000,"endTime":1788381000000,
                   "fights":[{"id":7,"name":"Voidscar Arena","keystoneLevel":14,
                     "keystoneTime":1500000,"keystoneBonus":1,"startTime":0,"endTime":1500000,
                     "friendlyPlayers":[42]}],
                   "masterData":{"actors":[{"id":42,"name":"Bucothered","server":"Stormscale"}]}}
                ]}}}}
                """);
        JsonNode pendingMetrics = emptyRankingResponse();
        JsonNode availableMetrics = rankingResponse(99, 93);
        when(client.rateLimit()).thenReturn(new WarcraftLogsClient.RateLimit(1000, 0, 3600));
        when(client.query(anyString(), anyMap()))
                .thenReturn(reports, pendingMetrics, reports, availableMetrics);
        when(runRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of(pendingRun));
        when(runRepository.save(any(WarcraftLogPlayerRunEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.findBySeasonKeyAndProfileId("midnight-season-2", 1))
                .thenReturn(Optional.empty());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Buco", "eu", "Stormscale", "Bucothered")
        ));
        when(eventPager.events("pending", 7, EventType.INTERRUPTS)).thenReturn(List.of());
        when(eventPager.events("pending", 7, EventType.DEATHS)).thenReturn(List.of());
        when(eventPager.events("pending", 7, EventType.DAMAGE_TAKEN)).thenReturn(List.of());
        WarcraftLogsStatisticsService service = new WarcraftLogsStatisticsService(
                client,
                properties(),
                trackedPlayerService,
                runRepository,
                snapshotRepository,
                correlationService,
                eventPager,
                mock(WarcraftLogItemLevelRepository.class)
        );

        service.refresh();
        assertThat(pendingRun.getKeyParsePercentage()).isNull();

        service.refresh();

        assertThat(pendingRun.getParsePercentage()).isEqualByComparingTo("99");
        assertThat(pendingRun.getKeyParsePercentage()).isEqualByComparingTo("93");
        verify(runRepository, times(2)).save(eq(pendingRun));
    }

    @Test
    void backfillsTimedStatusAndAvoidableDamageForAnAlreadyStoredRun() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        WarcraftLogsEventPager eventPager = mock(WarcraftLogsEventPager.class);
        WarcraftLogPlayerRunEntity storedRun = run(
                1L, "Thelinq", "stored", 9, 1, "80", "150000"
        );
        ReflectionTestUtils.setField(storedRun, "timed", null);
        JsonNode reports = mapper.readTree("""
                {"characterData":{"character":{"recentReports":{"data":[]}}}}
                """);
        JsonNode storedReport = mapper.readTree("""
                {"reportData":{"report":{"revision":1,
                  "fights":[{"id":7,"keystoneTime":1800000,"keystoneBonus":1}],
                  "masterData":{"actors":[{"id":42,"name":"Thelinq","server":"Stormscale"}]}
                }}}
                """);
        JsonNode emptyReportData = mapper.readTree("""
                {"reportData":{"report":{}}}
                """);
        JsonNode avoidableEvents = mapper.readTree("""
                [{"targetID":42,"isAvoidable":true,"amount":325000}]
                """);
        when(client.rateLimit()).thenReturn(new WarcraftLogsClient.RateLimit(1000, 0, 3600));
        when(client.query(anyString(), anyMap())).thenReturn(reports, storedReport, emptyReportData);
        when(runRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of(storedRun));
        when(runRepository.save(any(WarcraftLogPlayerRunEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.findBySeasonKeyAndProfileId("midnight-season-2", 1))
                .thenReturn(Optional.empty());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Linq", "eu", "Stormscale", "Thelinq")
        ));
        when(eventPager.events("stored", 7, EventType.INTERRUPTS)).thenReturn(List.of());
        when(eventPager.events("stored", 7, EventType.DEATHS)).thenReturn(List.of());
        when(eventPager.events("stored", 7, EventType.DAMAGE_TAKEN)).thenReturn(List.of(avoidableEvents.get(0)));
        WarcraftLogsStatisticsService service = new WarcraftLogsStatisticsService(
                client,
                properties(),
                trackedPlayerService,
                runRepository,
                snapshotRepository,
                mock(MPlusRunCorrelationService.class),
                eventPager,
                mock(WarcraftLogItemLevelRepository.class)
        );

        service.refresh();

        verify(runRepository).save(eq(storedRun));
        assertThat(storedRun.getAvoidableDamage()).isEqualByComparingTo("325000");
        assertThat(storedRun.getMetricsVersion())
                .isEqualTo(WarcraftLogPlayerRunEntity.CURRENT_METRICS_VERSION);
        assertThat(storedRun.getTimed()).isTrue();
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
        when(runRepository.findTimedBySeasonKeyAndMinimumKeystoneLevel("midnight-season-2", 12))
                .thenReturn(List.of(incompleteRun));
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
                .isEqualTo("No logged timed +12 or higher Warcraft Logs combat runs are available.")
                .doesNotContain("Key parse: unavailable");
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
                mock(WarcraftLogsEventPager.class),
                mock(WarcraftLogItemLevelRepository.class)
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
        return run(profileId, characterName, reportCode, interrupts, deaths, keyParse, damagePerSecond, null);
    }

    private static WarcraftLogPlayerRunEntity run(
            long profileId,
            String characterName,
            String reportCode,
            int interrupts,
            int deaths,
            String keyParse,
            String damagePerSecond,
            String avoidableDamage
    ) {
        return runAtLevel(
                10, profileId, characterName, reportCode, interrupts, deaths,
                keyParse, damagePerSecond, avoidableDamage
        );
    }

    private static WarcraftLogPlayerRunEntity runAtLevel(
            int keystoneLevel,
            long profileId,
            String characterName,
            String reportCode,
            int interrupts,
            int deaths,
            String keyParse,
            String damagePerSecond,
            String avoidableDamage
    ) {
        WarcraftLogPlayerRunEntity run = new WarcraftLogPlayerRunEntity(
                "midnight-season-2", profileId, characterName, reportCode, 1,
                Instant.parse("2026-08-19T10:00:00Z"), 7, "King's Rest", keystoneLevel,
                interrupts, deaths, null,
                keyParse == null ? null : new BigDecimal(keyParse),
                new BigDecimal(damagePerSecond)
        );
        run.recordAvoidableDamage(avoidableDamage == null ? null : new BigDecimal(avoidableDamage));
        if (keyParse != null) {
            run.recordCompletion(1_800_000, true);
        }
        return run;
    }

    private static JsonNode rankingResponse(int normalParse, int keyParse) throws Exception {
        return new ObjectMapper().readTree("""
                {"reportData":{"report":{
                  "p7":{"characters":[{
                    "id":42,"name":"Bucothered","rankPercent":%d,"bracketPercent":%d
                  }]},
                  "d7":{"data":{
                    "entries":[{"id":42,"name":"Bucothered","total":270000000}],
                    "totalTime":1500000
                  }}
                }}}
                """.formatted(normalParse, keyParse));
    }

    private static JsonNode emptyRankingResponse() throws Exception {
        return new ObjectMapper().readTree("""
                {"reportData":{"report":{
                  "p7":{},
                  "d7":{"data":{
                    "entries":[{"id":42,"name":"Bucothered","total":270000000}],
                    "totalTime":1500000
                  }}
                }}}
                """);
    }

    private static WarcraftLogsProperties properties() {
        return new WarcraftLogsProperties(
                "", "", "", "", 10, true, "midnight-season-2", Instant.EPOCH, 80, 12
        );
    }
}
