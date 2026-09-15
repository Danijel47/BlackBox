package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.entity.WarcraftLogPlayerRunEntity;
import com.blackbox.wow.repository.WarcraftLogPlayerRunRepository;
import com.blackbox.wow.repository.WarcraftLogProfileSnapshotRepository;
import com.blackbox.wow.repository.WarcraftLogItemLevelRepository;
import com.blackbox.wow.service.MPlusRunCorrelationService;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.service.CombatKeyLevelService;
import com.blackbox.wow.repository.CombatKeyLevelRepository;
import com.blackbox.wow.warcraftlogs.WarcraftLogsEventPager.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WarcraftLogsStatisticsServiceTest {

    private static final int MINIMUM_KEYSTONE_LEVEL = 13;
    private static final String RUBY_LIFE_POOLS = "Ruby Life Pools";

    @Test
    void combatAndAwardsUseTheLatestPersistedLevelWithoutRefreshingLogs() {
        CombatKeyLevelRepository settings = mock(CombatKeyLevelRepository.class);
        when(settings.findLevel()).thenReturn(Optional.of(12), Optional.of(18), Optional.of(18), Optional.of(12));
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        WarcraftLogPlayerRunRepository runs = mock(WarcraftLogPlayerRunRepository.class);
        TrackedPlayerService players = mock(TrackedPlayerService.class);
        when(players.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Linq", "eu", "Stormscale", "Thelinq")));
        List<WarcraftLogPlayerRunEntity> stored = List.of(
                runAtLevel(12, 1, "Thelinq", "lower", 4, 0, "80", "100000", null),
                runAtLevel(18, 1, "Thelinq", "higher", 8, 0, "90", "200000", null));
        when(runs.findTimedBySeasonKeyAndMinimumKeystoneLevel(eq("midnight-season-2"), anyInt()))
                .thenAnswer(invocation -> stored.stream()
                        .filter(run -> run.getKeystoneLevel() >= (int) invocation.getArgument(1)).toList());
        WarcraftLogsStatisticsService service = service(
                client, new WarcraftLogsRaidStatisticsService(client), properties(), players, runs,
                mock(WarcraftLogProfileSnapshotRepository.class),
                mock(MPlusRunCorrelationService.class), mock(WarcraftLogsEventPager.class),
                mock(WarcraftLogItemLevelRepository.class), new CombatKeyLevelService(settings, properties(), 999L));

        assertThat(service.combatMessage(""))
                .contains("timed +12 and above", "Logged runs: 2", "DPS: 150k", "timed +12 or higher runs");
        assertThat(service.combatMessage("Linq"))
                .contains("timed +18 and above", "Logged runs: 1", "DPS: 200k", "timed +18 or higher runs");
        assertThat(service.awardsMessage()).contains("timed +18 and above", "Timed +18 or higher runs only");
        assertThat(service.awardsMessage()).contains("timed +12 and above", "Timed +12 or higher runs only");

        verify(settings, times(4)).findLevel();
        verify(runs, times(2)).findTimedBySeasonKeyAndMinimumKeystoneLevel("midnight-season-2", 12);
        verify(runs, times(2)).findTimedBySeasonKeyAndMinimumKeystoneLevel("midnight-season-2", 18);
        verifyNoInteractions(client);
    }

    @Test
    void buildsOnDemandCombatMessagesWithoutRefreshingWarcraftLogs() {
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        when(runRepository.findTimedBySeasonKeyAndMinimumKeystoneLevel(
                "midnight-season-2", MINIMUM_KEYSTONE_LEVEL
        ))
                .thenReturn(List.of());
        when(snapshotRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of());
        WarcraftLogsStatisticsService service = service(
                client,
                new WarcraftLogsRaidStatisticsService(client),
                properties(),
                trackedPlayerService,
                runRepository,
                snapshotRepository,
                mock(MPlusRunCorrelationService.class),
                mock(WarcraftLogsEventPager.class),
                mock(WarcraftLogItemLevelRepository.class),
                keyLevels()
        );

        assertThat(service.combatMessage(""))
                .isEqualTo("No logged timed +13 or higher Warcraft Logs combat runs are available.");
        assertThat(service.combatMessage(""))
                .isEqualTo("No logged timed +13 or higher Warcraft Logs combat runs are available.");

        verifyNoInteractions(client);
        verify(runRepository, times(2)).findTimedBySeasonKeyAndMinimumKeystoneLevel(
                "midnight-season-2", MINIMUM_KEYSTONE_LEVEL
        );
    }

    @Test
    void stopsProfileCollectionAndBlocksRefreshesAfterWarcraftLogsReturns429() {
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        when(client.rateLimit()).thenReturn(new WarcraftLogsClient.RateLimit(1000, 0, 3600));
        when(runRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Linq", "eu", "Stormscale", "Thelinq"),
                new TrackedPlayerService.TrackedPlayer(2, "Buco", "eu", "Stormscale", "Bucothered")
        ));
        when(client.query(anyString(), anyMap())).thenThrow(new WarcraftLogsClient.RateLimitExceededException(
                Duration.ofMinutes(30), null
        ));
        WarcraftLogsStatisticsService service = service(
                client,
                new WarcraftLogsRaidStatisticsService(client),
                properties(),
                trackedPlayerService,
                runRepository,
                mock(WarcraftLogProfileSnapshotRepository.class),
                mock(MPlusRunCorrelationService.class),
                mock(WarcraftLogsEventPager.class),
                mock(WarcraftLogItemLevelRepository.class),
                keyLevels()
        );

        service.refresh();
        service.refresh();

        verify(client, times(1)).rateLimit();
        verify(client, times(1)).query(anyString(), anyMap());
        verify(runRepository, times(1)).findBySeasonKey("midnight-season-2");
    }

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
        when(runRepository.findTimedBySeasonKeyAndMinimumKeystoneLevel(
                "midnight-season-2", MINIMUM_KEYSTONE_LEVEL
        ))
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
        when(runRepository.findTimedBySeasonKeyAndMinimumKeystoneLevel(
                "midnight-season-2", MINIMUM_KEYSTONE_LEVEL
        ))
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
        when(runRepository.findTimedBySeasonKeyAndMinimumKeystoneLevel(
                "midnight-season-2", MINIMUM_KEYSTONE_LEVEL
        ))
                .thenReturn(List.of(
                        runAtLevel(
                                MINIMUM_KEYSTONE_LEVEL, 1L, "Thelinq", "level-thirteen",
                                4, 0, "80", "200000", "4000"
                        )
                ));
        when(snapshotRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Linq", "eu", "Stormscale", "Thelinq")
        ));

        String message = service(trackedPlayerService, runRepository, snapshotRepository)
                .combatMessage("Linq");

        assertThat(message)
                .contains("Warcraft Logs M+ combat (timed +13 and above) — midnight-season-2")
                .contains("Key parse: 80%", "DPS: 200k")
                .contains("Interrupts per run: 4", "Deaths per run: 0", "Logged runs: 1")
                .contains("Averages use distinct logged timed +13 or higher runs")
                .contains("Duplicate uploads are identified by Warcraft Logs fight time and duration")
                .contains("depleted, missing, and private logs are excluded")
                .doesNotContain("Avoidable damage", "Key parse: 40%", "Interrupts per run: 20");
        verify(runRepository).findTimedBySeasonKeyAndMinimumKeystoneLevel(
                "midnight-season-2", MINIMUM_KEYSTONE_LEVEL
        );
    }

    @Test
    void excludesPendingZeroPercentilesWithoutDroppingOtherCombatMetrics() {
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        when(runRepository.findTimedBySeasonKeyAndMinimumKeystoneLevel(
                "midnight-season-2", MINIMUM_KEYSTONE_LEVEL
        ))
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
    void excludesDuplicateUploadsUsingTheAbsoluteFightWindow() {
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        WarcraftLogPlayerRunEntity original = runAtLevel(
                14, 1L, "Linqq", "original", 4, 0, "80", "200000", null
        );
        original.recordFightWindow(
                Instant.parse("2026-08-31T10:05:00Z"), Instant.parse("2026-08-31T10:35:00Z")
        );
        WarcraftLogPlayerRunEntity duplicate = runAtLevel(
                14, 1L, "Linqq", "duplicate", 30, 10, "80", "200000", null
        );
        duplicate.recordFightWindow(
                Instant.parse("2026-08-31T10:06:00Z"), Instant.parse("2026-08-31T10:36:00Z")
        );
        WarcraftLogPlayerRunEntity separateRun = runAtLevel(
                14, 1L, "Linqq", "separate", 8, 2, "90", "300000", null
        );
        separateRun.recordFightWindow(
                Instant.parse("2026-08-31T10:30:00Z"), Instant.parse("2026-08-31T11:00:00Z")
        );
        when(runRepository.findTimedBySeasonKeyAndMinimumKeystoneLevel(
                "midnight-season-2", MINIMUM_KEYSTONE_LEVEL
        ))
                .thenReturn(List.of(original, duplicate, separateRun));
        when(snapshotRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Linq", "eu", "Stormscale", "Linqq")
        ));

        String message = service(trackedPlayerService, runRepository, snapshotRepository)
                .combatMessage("Linq");

        assertThat(message)
                .contains("Key parse: 85%", "DPS: 250k")
                .contains("Interrupts per run: 6", "Deaths per run: 1")
                .contains("Logged runs: 2", "Duplicate uploads excluded: 1")
                .doesNotContain("Interrupts per run: 14");
    }

    @Test
    void formatsReadableCombatAwardsFromLoggedRuns() {
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        when(runRepository.findTimedBySeasonKeyAndMinimumKeystoneLevel(
                "midnight-season-2", MINIMUM_KEYSTONE_LEVEL
        ))
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
                .contains("🏆 M+ Awards — timed +13 and above — midnight-season-2")
                .contains("⬆️ Maximum awards")
                .contains("💀 Floor POV — Most deaths per run", "• Buco (BucoMain)", "Deaths/run: 4")
                .contains("🛑 CC Machine — Most interrupts per run", "• Linq (LinqMain)", "Interrupts/run: 12")
                .contains("🔥 Top Pumper — Best average key parse", "• Lazo (LazoMain)", "Key parse: 95%")
                .contains("⬇️ Minimum awards")
                .contains("🪽 Not Today, Spirit Healer — Fewest deaths per run", "Deaths/run: 0")
                .contains("💿 My Kick Was on Cooldown — Fewest interrupts per run", "Interrupts/run: 2")
                .contains("🎮 Are You Pressing Buttons? — Lowest average key parse", "Key parse: 70%")
                .contains("Timed +13 or higher runs only")
                .contains("Duplicate uploads are identified by Warcraft Logs fight time and duration")
                .contains("depleted, missing, and private logs are excluded")
                .doesNotContain("Avoidable damage", "Stand in Fire", "Fire Bad", "formula:", "N=");
        verify(runRepository).findTimedBySeasonKeyAndMinimumKeystoneLevel(
                "midnight-season-2", MINIMUM_KEYSTONE_LEVEL
        );
    }

    @Test
    void asksUsersToWaitWhileCombatDataIsRefreshing() {
        WarcraftLogsStatisticsService service = service(
                mock(TrackedPlayerService.class),
                mock(WarcraftLogPlayerRunRepository.class),
                mock(WarcraftLogProfileSnapshotRepository.class)
        );
        WarcraftLogsCollectionService collectionService =
                (WarcraftLogsCollectionService) ReflectionTestUtils.getField(service, "collectionService");
        assertThat(collectionService).isNotNull();
        AtomicBoolean refreshRunning =
                (AtomicBoolean) ReflectionTestUtils.getField(collectionService, "refreshRunning");
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

        assertThat(WarcraftLogsEventParser.sumAvoidableDamageForActor(
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

        assertThat(WarcraftLogsEventParser.sumAvoidableDamageForActor(
                events, 42, RUBY_LIFE_POOLS
        )).isEqualByComparingTo("120000");
    }

    @Test
    void recordsZeroWhenSupportedDungeonHasNoAvoidableHits() throws Exception {
        JsonNode events = new ObjectMapper().readTree("""
                [{"targetID":42,"abilityGameID":372735,"amount":120000}]
                """);

        assertThat(WarcraftLogsEventParser.sumAvoidableDamageForActor(
                events, 42, RUBY_LIFE_POOLS
        )).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void keepsAvoidableDamageUnavailableForAnUnsupportedDungeon() throws Exception {
        JsonNode events = new ObjectMapper().readTree("""
                [{"targetID":42,"abilityGameID":373614,"amount":120000}]
                """);

        assertThat(WarcraftLogsEventParser.sumAvoidableDamageForActor(
                events, 42, "Unknown Dungeon"
        )).isNull();
    }

    @Test
    void explicitWarcraftLogsClassificationOverridesTheCatalogue() throws Exception {
        JsonNode events = new ObjectMapper().readTree("""
                [{"targetID":42,"abilityGameID":373614,"isAvoidable":false,"amount":120000}]
                """);

        assertThat(WarcraftLogsEventParser.sumAvoidableDamageForActor(
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
        WarcraftLogsStatisticsService service = service(
                client,
                new WarcraftLogsRaidStatisticsService(client),
                properties(),
                trackedPlayerService,
                mock(WarcraftLogPlayerRunRepository.class),
                mock(WarcraftLogProfileSnapshotRepository.class),
                mock(MPlusRunCorrelationService.class),
                mock(WarcraftLogsEventPager.class),
                mock(WarcraftLogItemLevelRepository.class),
                keyLevels()
        );

        String message = service.raidCombatMessage(List.of(
                new TrackedPlayerService.TrackedPlayer(1L, "Buco", "eu", "Stormscale", "Bucothered")
        ));

        assertThat(message)
                .contains("Raid Combat — best performance average")
                .contains("Normal: 81.69%", "Heroic: 24.69%", "Mythic: —")
                .containsSubsequence("Mythic: —", "Heroic: 24.69%", "Normal: 81.69%")
                .doesNotContain("81.68728134929741", "24.692101313564862");
    }

    @ParameterizedTest
    @CsvSource({
            "null, null, 60, null, null, 85, Linq, Buco",
            "null, 95, 99, 40, null, null, Linq, Buco",
            "null, null, 99, null, 40, null, Linq, Buco",
            "40, 99, 99, 50, 10, 10, Linq, Buco",
            "null, 40, 99, null, 50, 10, Linq, Buco",
            "null, 95, 99, 0, null, null, Linq, Buco",
            "null, null, null, null, null, 0, Linq, Buco",
            "40, 10, 10, 40, 99, 99, Buco, Linq",
            "null, null, null, null, null, null, Buco, Linq"
    })
    void sortsRaidCombatProfilesByHighestAvailableDifficulty(
            String bucoMythic, String bucoHeroic, String bucoNormal,
            String linqMythic, String linqHeroic, String linqNormal,
            String first, String second
    ) throws Exception {
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode buco = mapper.readTree("""
                {"characterData":{"character":{"name":"Bucothered",
                  "normal":{"bestPerformanceAverage":%s},
                  "heroic":{"bestPerformanceAverage":%s},
                  "mythic":{"bestPerformanceAverage":%s}}}}
                """.formatted(bucoNormal, bucoHeroic, bucoMythic));
        JsonNode linq = mapper.readTree("""
                {"characterData":{"character":{"name":"Thelinq",
                  "normal":{"bestPerformanceAverage":%s},
                  "heroic":{"bestPerformanceAverage":%s},
                  "mythic":{"bestPerformanceAverage":%s}}}}
                """.formatted(linqNormal, linqHeroic, linqMythic));
        when(client.query(anyString(), anyMap())).thenReturn(linq, buco)
                .thenThrow(new IllegalStateException("rankings unavailable"));
        WarcraftLogsStatisticsService service = service(
                client,
                new WarcraftLogsRaidStatisticsService(client),
                properties(),
                trackedPlayerService,
                mock(WarcraftLogPlayerRunRepository.class),
                mock(WarcraftLogProfileSnapshotRepository.class),
                mock(MPlusRunCorrelationService.class),
                mock(WarcraftLogsEventPager.class),
                mock(WarcraftLogItemLevelRepository.class),
                keyLevels()
        );

        String report = service.raidCombatMessage(List.of(
                new TrackedPlayerService.TrackedPlayer(2L, "Linq", "eu", "Draenor", "Thelinq"),
                new TrackedPlayerService.TrackedPlayer(1L, "Buco", "eu", "Stormscale", "Bucothered"),
                new TrackedPlayerService.TrackedPlayer(3L, "Unavailable", "eu", "Draenor", "Missing")
        ));

        assertThat(report).containsSubsequence("• " + first, "• " + second,
                "• Unavailable (Missing): unavailable");
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

        assertThat(WarcraftLogsEventParser.isEligibleFight(
                completed, 3, MINIMUM_KEYSTONE_LEVEL, 42, MINIMUM_KEYSTONE_LEVEL
        )).isTrue();
        assertThat(WarcraftLogsEventParser.isEligibleFight(
                completed, 3, MINIMUM_KEYSTONE_LEVEL - 1, 42, MINIMUM_KEYSTONE_LEVEL
        )).isFalse();
        assertThat(WarcraftLogsEventParser.isEligibleFight(
                partial, 1, MINIMUM_KEYSTONE_LEVEL, 42, MINIMUM_KEYSTONE_LEVEL
        )).isFalse();
    }

    @Test
    void skipsCombatCollectionAndBackfillsBelowTheSupportedKeyLevel() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        WarcraftLogsEventPager eventPager = mock(WarcraftLogsEventPager.class);
        WarcraftLogPlayerRunEntity storedRun = runAtLevel(
                CombatKeyLevelService.MIN_LEVEL - 1,
                1L,
                "Thelinq",
                "below-minimum",
                0,
                0,
                "80",
                "150000",
                null
        );
        ReflectionTestUtils.setField(storedRun, "timed", null);
        JsonNode reports = mapper.readTree("""
                {"characterData":{"character":{"recentReports":{"data":[
                  {"code":"below-minimum","revision":1,"startTime":1000,"endTime":5000,
                   "fights":[{"id":7,"name":"Ruby Life Pools","keystoneLevel":%d,
                     "keystoneTime":3000,"keystoneBonus":1,"startTime":0,"endTime":3000,
                     "friendlyPlayers":[42]}],
                   "masterData":{"actors":[{"id":42,"name":"Thelinq","server":"Stormscale"}]}}
                ]}}}}
                """.formatted(CombatKeyLevelService.MIN_LEVEL - 1));
        when(client.rateLimit()).thenReturn(new WarcraftLogsClient.RateLimit(1000, 0, 3600));
        when(client.query(anyString(), anyMap())).thenReturn(reports);
        when(runRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of(storedRun));
        when(snapshotRepository.findBySeasonKeyAndProfileId("midnight-season-2", 1))
                .thenReturn(Optional.empty());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Linq", "eu", "Stormscale", "Thelinq")
        ));
        WarcraftLogsStatisticsService service = service(
                client,
                new WarcraftLogsRaidStatisticsService(client),
                properties(),
                trackedPlayerService,
                runRepository,
                snapshotRepository,
                mock(MPlusRunCorrelationService.class),
                eventPager,
                mock(WarcraftLogItemLevelRepository.class),
                keyLevels()
        );

        service.refresh();

        verify(client, times(1)).query(anyString(), anyMap());
        verify(runRepository, times(0)).save(any(WarcraftLogPlayerRunEntity.class));
        verifyNoInteractions(eventPager);
    }

    @Test
    void matchesHistoricalItemLevelToTheFriendlyPlayer() throws Exception {
        JsonNode fight = new ObjectMapper().readTree("""
                {"friendlyPlayers":[7,2,9],"friendlyItemLevels":[305,301,298]}
                """);

        assertThat(WarcraftLogsEventParser.findFriendlyItemLevel(fight, 2))
                .isEqualByComparingTo("301");
    }

    @ParameterizedTest
    @ValueSource(ints = {12, 13, 18, 19})
    void collectsDuplicateUploadsOnlyOnceAcrossSupportedLevels(int level) throws Exception {
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
                   "fights":[{"id":1,"name":"Ruby Life Pools","keystoneLevel":%d,
                     "keystoneTime":3000,"keystoneBonus":1,"startTime":0,"endTime":3000,
                     "friendlyPlayers":[42],"friendlyItemLevels":[303]}],
                   "masterData":{"actors":[{"id":42,"name":"Thelinq","server":"Stormscale"}]}},
                  {"code":"duplicate","revision":1,"startTime":1000,"endTime":5000,
                   "fights":[{"id":1,"name":"Ruby Life Pools","keystoneLevel":%d,
                     "keystoneTime":3000,"keystoneBonus":1,"startTime":0,"endTime":3000,
                     "friendlyPlayers":[42],"friendlyItemLevels":[303]}],
                   "masterData":{"actors":[{"id":42,"name":"Thelinq","server":"Stormscale"}]}}
                ]}}}}
                """.formatted(level, level));
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
        WarcraftLogsStatisticsService service = service(
                client,
                new WarcraftLogsRaidStatisticsService(client),
                properties(),
                trackedPlayerService,
                runRepository,
                snapshotRepository,
                mock(MPlusRunCorrelationService.class),
                eventPager,
                itemLevelRepository,
                keyLevels()
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
        WarcraftLogsStatisticsService service = service(
                client,
                new WarcraftLogsRaidStatisticsService(client),
                properties(),
                trackedPlayerService,
                runRepository,
                snapshotRepository,
                correlationService,
                eventPager,
                mock(WarcraftLogItemLevelRepository.class),
                keyLevels()
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
                  "fights":[{"id":7,"keystoneTime":1800000,"keystoneBonus":1,
                    "startTime":300000,"endTime":2100000}],
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
        WarcraftLogsStatisticsService service = service(
                client,
                new WarcraftLogsRaidStatisticsService(client),
                properties(),
                trackedPlayerService,
                runRepository,
                snapshotRepository,
                mock(MPlusRunCorrelationService.class),
                eventPager,
                mock(WarcraftLogItemLevelRepository.class),
                keyLevels()
        );

        service.refresh();

        verify(runRepository).save(eq(storedRun));
        assertThat(storedRun.getAvoidableDamage()).isEqualByComparingTo("325000");
        assertThat(storedRun.getMetricsVersion())
                .isEqualTo(WarcraftLogPlayerRunEntity.CURRENT_METRICS_VERSION);
        assertThat(storedRun.getTimed()).isTrue();
        assertThat(storedRun.getFightStartedAt()).isEqualTo("2026-08-19T10:05:00Z");
        assertThat(storedRun.getFightEndedAt()).isEqualTo("2026-08-19T10:35:00Z");
    }

    @Test
    void backfillsHistoricalFightTimesWithoutReloadingCombatEvents() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        WarcraftLogPlayerRunRepository runRepository = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshotRepository =
                mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService trackedPlayerService = mock(TrackedPlayerService.class);
        WarcraftLogsEventPager eventPager = mock(WarcraftLogsEventPager.class);
        WarcraftLogPlayerRunEntity storedRun = runAtLevel(
                14, 1L, "Linqq", "historical", 9, 1, "90", "250000", null
        );
        JsonNode reports = mapper.readTree("""
                {"characterData":{"character":{"recentReports":{"data":[]}}}}
                """);
        JsonNode storedReport = mapper.readTree("""
                {"reportData":{"report":{"revision":1,
                  "fights":[{"id":7,"keystoneTime":1800000,"keystoneBonus":1,
                    "startTime":300000,"endTime":2100000}]
                }}}
                """);
        when(client.rateLimit()).thenReturn(new WarcraftLogsClient.RateLimit(1000, 0, 3600));
        when(client.query(anyString(), anyMap())).thenReturn(reports, storedReport);
        when(runRepository.findBySeasonKey("midnight-season-2")).thenReturn(List.of(storedRun));
        when(runRepository.save(any(WarcraftLogPlayerRunEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.findBySeasonKeyAndProfileId("midnight-season-2", 1))
                .thenReturn(Optional.empty());
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Linq", "eu", "Stormscale", "Linqq")
        ));
        WarcraftLogsStatisticsService service = service(
                client,
                new WarcraftLogsRaidStatisticsService(client),
                properties(),
                trackedPlayerService,
                runRepository,
                snapshotRepository,
                mock(MPlusRunCorrelationService.class),
                eventPager,
                mock(WarcraftLogItemLevelRepository.class),
                keyLevels()
        );

        service.refresh();

        assertThat(storedRun.getFightStartedAt()).isEqualTo("2026-08-19T10:05:00Z");
        assertThat(storedRun.getFightEndedAt()).isEqualTo("2026-08-19T10:35:00Z");
        verify(runRepository).save(storedRun);
        verifyNoInteractions(eventPager);
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
        when(runRepository.findTimedBySeasonKeyAndMinimumKeystoneLevel(
                "midnight-season-2", MINIMUM_KEYSTONE_LEVEL
        ))
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
                .isEqualTo("No logged timed +13 or higher Warcraft Logs combat runs are available.")
                .doesNotContain("Key parse: unavailable");
    }

    private static CombatKeyLevelService keyLevels() {
        return new CombatKeyLevelService(mock(CombatKeyLevelRepository.class), properties(), 999L);
    }

    private static WarcraftLogsStatisticsService service(
            WarcraftLogsClient client,
            WarcraftLogsRaidStatisticsService raidStatisticsService,
            WarcraftLogsProperties properties,
            TrackedPlayerService trackedPlayerService,
            WarcraftLogPlayerRunRepository runRepository,
            WarcraftLogProfileSnapshotRepository snapshotRepository,
            MPlusRunCorrelationService correlationService,
            WarcraftLogsEventPager eventPager,
            WarcraftLogItemLevelRepository itemLevelRepository,
            CombatKeyLevelService combatKeyLevelService
    ) {
        WarcraftLogsCollectionService collectionService = new WarcraftLogsCollectionService(
                client, properties, trackedPlayerService, runRepository, snapshotRepository,
                correlationService, eventPager, itemLevelRepository
        );
        return new WarcraftLogsStatisticsService(
                properties, trackedPlayerService, runRepository, snapshotRepository,
                combatKeyLevelService, raidStatisticsService, collectionService
        );
    }

    private static WarcraftLogsStatisticsService service(
            TrackedPlayerService trackedPlayerService,
            WarcraftLogPlayerRunRepository runRepository,
            WarcraftLogProfileSnapshotRepository snapshotRepository
    ) {
        return service(
                mock(WarcraftLogsClient.class),
                mock(WarcraftLogsRaidStatisticsService.class),
                properties(),
                trackedPlayerService,
                runRepository,
                snapshotRepository,
                mock(MPlusRunCorrelationService.class),
                mock(WarcraftLogsEventPager.class),
                mock(WarcraftLogItemLevelRepository.class),
                keyLevels()
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
                MINIMUM_KEYSTONE_LEVEL, profileId, characterName, reportCode, interrupts, deaths,
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
                "", "", "", "", 10, true, "midnight-season-2", Instant.EPOCH, 80,
                MINIMUM_KEYSTONE_LEVEL
        );
    }
}
