package com.blackbox.wow.service;

import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.client.RaiderIoClient.MPlusRun;
import com.blackbox.wow.client.RaiderIoClient.WeeklyVaultProgress;
import com.blackbox.wow.properties.MPlusDungeonProperties;
import com.blackbox.wow.repository.MPlusDungeonVaultRepository;
import com.blackbox.wow.repository.MPlusDungeonVaultRepository.DungeonCoverage;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MPlusDungeonVaultServiceTest {

    @Mock private MPlusPlayerResolver playerResolver;
    @Mock private RaiderIoClient raiderIoClient;
    @Mock private MPlusProgressRepository progressRepository;
    @Mock private MPlusDungeonVaultRepository dungeonVaultRepository;

    @Test
    void showsTimedTargetCoverageWithoutCountingMissingOrDepletedRuns() {
        TrackedPlayer player = player("Bucomonk");
        when(playerResolver.resolveSelfOrNamed("", 123L)).thenReturn(Resolution.found(player));
        when(progressRepository.latestScore(1)).thenReturn(Optional.of(score()));
        when(dungeonVaultRepository.dungeonCoverage(1, "season-mn-2")).thenReturn(List.of(
                new DungeonCoverage(1, 101, "Alpha Dungeon", "AD", 12, new BigDecimal("320.4")),
                new DungeonCoverage(2, 102, "Beta Dungeon", "BD", 8, new BigDecimal("250.1")),
                new DungeonCoverage(3, 103, "Gamma Dungeon", "GD", null, null)
        ));

        String message = service().dungeonCoverageMessage("", 123L);

        assertThat(message)
                .contains("Timed +10 coverage: 1/3")
                .contains("AD — +12 timed | score 320.4")
                .contains("GD — no timed run | score unavailable")
                .contains("Missing timed dungeons: GD")
                .contains("depleted runs do not satisfy coverage");
    }

    @Test
    void showsOnlyTheSelectedMainsCurrentVaultProgress() {
        TrackedPlayer currentPlayer = player("Bucomonk");
        when(playerResolver.resolveSelfOrNamed("Buco", 999L)).thenReturn(Resolution.found(currentPlayer));
        when(raiderIoClient.getWeeklyVaultProgress("eu", "Stormscale", "Bucomonk"))
                .thenReturn(new WeeklyVaultProgress(
                        "Bucomonk",
                        "Stormscale",
                        "eu",
                        List.of(
                                new MPlusRun(11, "Kings' Rest", "2026-08-20T10:00:00Z"),
                                new MPlusRun(9, "Ruby Life Pools", "2026-08-20T09:00:00Z"),
                                new MPlusRun(8, "Kings' Rest", "2026-08-20T08:00:00Z"),
                                new MPlusRun(6, "Ruby Life Pools", "2026-08-20T07:00:00Z")
                        ),
                        "https://raider.io/characters/eu/stormscale/Bucomonk"
                ));

        String message = service().currentVaultMessage("Buco", 999L);

        verify(raiderIoClient).getWeeklyVaultProgress("eu", "Stormscale", "Bucomonk");
        assertThat(message)
                .contains("Great Vault — current week — Mythic+ only — Buco")
                .contains("4 runs | 1: +11 | 4: +6 | 8: locked")
                .contains("• +11 Kings' Rest")
                .doesNotContain("history", "Week of", "streak");
    }

    private MPlusDungeonVaultService service() {
        return new MPlusDungeonVaultService(
                playerResolver,
                raiderIoClient,
                progressRepository,
                dungeonVaultRepository,
                new MPlusDungeonProperties(11, 10)
        );
    }

    private static TrackedPlayer player(String characterName) {
        return new TrackedPlayer(1, "Buco", "eu", "Stormscale", characterName);
    }

    private static ScorePoint score() {
        return new ScorePoint(
                1,
                "season-mn-2",
                new BigDecimal("2000"),
                Instant.parse("2026-08-19T10:00:00Z"),
                "eu",
                "Stormscale",
                "Bucomonk"
        );
    }

}
