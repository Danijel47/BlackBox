package com.blackbox.wow.repository;

import com.blackbox.wow.client.MPlusObservation.RunSummary;
import com.blackbox.wow.helper.VaultSlotCalculator;
import com.blackbox.wow.helper.VaultSlotCalculator.VaultSlots;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MPlusDungeonVaultRepositoryTest {

    @Test
    void usesRaiderIoWeeklyRunsForVaultSnapshot() {
        List<RunSummary> weeklyRuns = List.of(
                run(101L, 7),
                run(102L, 12),
                run(103L, 9),
                run(104L, 11)
        );

        List<Integer> levels = MPlusDungeonVaultRepository.runLevels(weeklyRuns);
        VaultSlots slots = VaultSlotCalculator.calculate(levels);

        assertThat(levels).containsExactly(12, 11, 9, 7);
        assertThat(slots.runCount()).isEqualTo(4);
        assertThat(slots.slotOne()).isEqualTo(12);
        assertThat(slots.slotFour()).isEqualTo(7);
        assertThat(slots.slotEight()).isNull();
    }

    @Test
    void recoversPreviousWeekFromOtherObservedRaiderIoRuns() {
        Instant periodStart = Instant.parse("2026-08-12T04:00:00Z");
        Instant periodEnd = Instant.parse("2026-08-19T04:00:00Z");
        List<RunSummary> observedRuns = List.of(
                run(101L, 7, "2026-08-12T03:59:59Z"),
                run(102L, 12, "2026-08-12T04:00:00Z"),
                run(103L, 9, "2026-08-18T20:00:00Z"),
                run(104L, 11, "2026-08-19T04:00:00Z")
        );

        List<Integer> levels = MPlusDungeonVaultRepository.runLevelsBetween(
                observedRuns,
                periodStart,
                periodEnd
        );

        assertThat(levels).containsExactly(12, 9);
    }

    private static RunSummary run(long id, int level) {
        return run(id, level, "2026-08-18T20:00:00Z");
    }

    private static RunSummary run(long id, int level, String completedAt) {
        return new RunSummary(
                id,
                "Kings' Rest",
                "KR",
                244,
                level,
                Instant.parse(completedAt),
                1_800_000,
                2_000_000,
                1,
                BigDecimal.valueOf(200),
                false,
                false,
                true
        );
    }
}
