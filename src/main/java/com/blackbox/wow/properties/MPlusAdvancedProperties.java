package com.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("wow.mplus-advanced")
public record MPlusAdvancedProperties(
        boolean awardsEnabled,
        int minimumIndividualRuns,
        int minimumSharedRuns,
        int comfortCoveragePercent,
        int specialistMinimumPercent,
        int clutchWindowSeconds,
        int maximumAffixRows
) {
    public MPlusAdvancedProperties {
        requireRange(minimumIndividualRuns, 2, 1_000, "minimumIndividualRuns");
        requireRange(minimumSharedRuns, 2, 1_000, "minimumSharedRuns");
        requireRange(comfortCoveragePercent, 50, 100, "comfortCoveragePercent");
        requireRange(specialistMinimumPercent, 1, 100, "specialistMinimumPercent");
        requireRange(clutchWindowSeconds, 1, 600, "clutchWindowSeconds");
        requireRange(maximumAffixRows, 1, 20, "maximumAffixRows");
    }

    private static void requireRange(int value, int minimum, int maximum, String property) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(property + " must be between " + minimum + " and " + maximum);
        }
    }
}
