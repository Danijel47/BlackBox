package com.blackbox.wow.helper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VaultSlotCalculatorTest {

    @Test
    void calculatesTheExistingOneFourAndEightRunSlotsFromUnsortedRuns() {
        VaultSlotCalculator.VaultSlots slots = VaultSlotCalculator.calculate(
                List.of(7, 12, 10, 9, 11, 8, 6, 10)
        );

        assertThat(slots.runCount()).isEqualTo(8);
        assertThat(slots.slotOne()).isEqualTo(12);
        assertThat(slots.slotFour()).isEqualTo(10);
        assertThat(slots.slotEight()).isEqualTo(6);
    }

    @Test
    void leavesUncompletedSlotsUnavailable() {
        VaultSlotCalculator.VaultSlots slots = VaultSlotCalculator.calculate(List.of(10, 9, 8));

        assertThat(slots.slotOne()).isEqualTo(10);
        assertThat(slots.slotFour()).isNull();
        assertThat(slots.slotEight()).isNull();
    }
}
