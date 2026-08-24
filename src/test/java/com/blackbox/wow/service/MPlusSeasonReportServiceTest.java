package com.blackbox.wow.service;

import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MPlusSeasonReportServiceTest {

    private static final String REGION = "eu";
    private static final String REALM = "stormscale";
    private static final String CHARACTER = "Bucothered";
    private static final String SEASON = "season-mn-1";

    @Mock private RaiderIoClient raiderIoClient;
    @Mock private RaiderIoAbandonedRunService abandonedRunService;
    @Mock private TrackedPlayerService trackedPlayerService;

    private MPlusSeasonReportService service;

    @BeforeEach
    void setUp() {
        service = new MPlusSeasonReportService(
                raiderIoClient,
                abandonedRunService,
                trackedPlayerService,
                Clock.fixed(Instant.parse("2026-08-24T10:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void reportsWhenNoVaultWatchProfilesAreConfigured() {
        when(trackedPlayerService.vaultWatchPlayers()).thenReturn(List.of());

        assertThat(service.weeklyVaultWatch()).isEqualTo("No Mythic+ vault watch players configured.");
    }

    @Test
    void formatsACharacterWeeklyVault() {
        when(raiderIoClient.getWeeklyVaultProgress(REGION, REALM, CHARACTER)).thenReturn(
                new RaiderIoClient.WeeklyVaultProgress(
                        CHARACTER,
                        "Stormscale",
                        REGION,
                        List.of(
                                new RaiderIoClient.MPlusRun(12, "Eco-Dome", ""),
                                new RaiderIoClient.MPlusRun(11, "Ara-Kara", ""),
                                new RaiderIoClient.MPlusRun(10, "Dawnbreaker", ""),
                                new RaiderIoClient.MPlusRun(9, "Priory", "")
                        ),
                        "https://raider.io/profile"
                )
        );

        String report = service.weeklyVault(REGION, REALM, CHARACTER);

        assertThat(report)
                .contains("Great Vault — Mythic+ only", "Slot 1 (1 run): +12", "Slot 2 (4 runs): +9")
                .contains("Slot 3 (8 runs): locked (4 more)", "1. +12 Eco-Dome")
                .contains("Profile: https://raider.io/profile");
    }

    @Test
    void combinesCompletedAndRecordedAbandonedRuns() {
        TrackedPlayer player = new TrackedPlayer(1L, "Buco", REGION, REALM, CHARACTER);
        when(trackedPlayerService.seasonRecapPlayers()).thenReturn(List.of(player));
        when(raiderIoClient.getMPlusSeasonRunCounts(REGION, REALM, CHARACTER, SEASON)).thenReturn(
                new RaiderIoClient.MPlusSeasonRunCounts(
                        CHARACTER,
                        "Stormscale",
                        REGION,
                        SEASON,
                        List.of(new RaiderIoClient.DungeonRunCount("Eco-Dome", "EDA", 8, 6)),
                        ""
                )
        );
        when(abandonedRunService.findLatest(REGION, REALM, CHARACTER, SEASON)).thenReturn(Optional.of(
                new RaiderIoAbandonedRunService.AbandonedRunSummary(
                        CHARACTER,
                        2,
                        10,
                        "Eco-Dome",
                        2,
                        Instant.EPOCH
                )
        ));

        String report = service.combinedRecap();

        assertThat(report)
                .contains("6 timed | 2 depleted | 2 abandoned")
                .contains("timed 60% | depleted 20% | abandoned 20%")
                .contains("Most played: EDA (8)", "Most abandoned: Eco-Dome (2)");
    }
}
