package com.blackbox.wow.helper;

import java.util.Comparator;
import java.util.List;

public final class VaultSlotCalculator {

    private VaultSlotCalculator() {
    }

    public static VaultSlots calculate(List<Integer> runLevels) {
        List<Integer> levels = runLevels == null ? List.of() : runLevels.stream()
                .filter(level -> level != null && level > 0)
                .sorted(Comparator.reverseOrder())
                .toList();
        return new VaultSlots(
                levels.size(),
                levelAt(levels, 1),
                levelAt(levels, 4),
                levelAt(levels, 8)
        );
    }

    private static Integer levelAt(List<Integer> levels, int requiredRuns) {
        return levels.size() < requiredRuns ? null : levels.get(requiredRuns - 1);
    }

    public record VaultSlots(int runCount, Integer slotOne, Integer slotFour, Integer slotEight) {
    }
}
