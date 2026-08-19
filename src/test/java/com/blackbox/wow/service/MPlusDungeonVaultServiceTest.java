package com.blackbox.wow.service;

import com.blackbox.wow.properties.MPlusDungeonProperties;
import com.blackbox.wow.properties.MPlusProgressProperties;
import com.blackbox.wow.repository.MPlusDungeonVaultRepository;
import com.blackbox.wow.repository.MPlusDungeonVaultRepository.DungeonCoverage;
import com.blackbox.wow.repository.MPlusDungeonVaultRepository.VaultHistory;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusProgressRepository.ScorePoint;
import com.blackbox.wow.service.MPlusPlayerResolver.Resolution;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MPlusDungeonVaultServiceTest {

    @Mock private MPlusPlayerResolver playerResolver;
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
    void keepsVaultHistoryAttachedToTheProfileAfterAMainChange() {
        TrackedPlayer currentPlayer = player("Bucomonk");
        when(playerResolver.resolveSelfOrNamed("Buco", 999L)).thenReturn(Resolution.found(currentPlayer));
        when(dungeonVaultRepository.vaultHistory(1, 8)).thenReturn(List.of(
                history("2026-08-05T04:00:00Z", "Bucothered", 8, 12),
                history("2026-08-12T04:00:00Z", "Bucothered", 8, 13)
        ));

        String message = service().vaultHistoryMessage("Buco", 999L);

        verify(dungeonVaultRepository).vaultHistory(1, 8);
        assertThat(message)
                .contains("Week of 2026-08-12")
                .contains("1: +13 | 4: +13 | 8: +13")
                .contains("Max-vault streak: 2 week(s)");
    }

    private MPlusDungeonVaultService service() {
        MPlusProgressProperties progress = new MPlusProgressProperties(
                ZoneId.of("UTC"), DayOfWeek.WEDNESDAY, LocalTime.of(4, 0), List.of()
        );
        return new MPlusDungeonVaultService(
                playerResolver,
                progressRepository,
                dungeonVaultRepository,
                new MPlusDungeonProperties(11, 10, 8),
                progress
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

    private static VaultHistory history(String period, String character, int runCount, int slot) {
        Instant captured = Instant.parse(period).plusSeconds(604_800);
        return new VaultHistory(
                "season-mn-2",
                Instant.parse(period),
                "eu",
                "Stormscale",
                character,
                runCount,
                slot,
                slot,
                slot,
                captured,
                captured
        );
    }
}
