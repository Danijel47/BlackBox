package com.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("wow.mplus-correlation")
public record MPlusCorrelationProperties(
        int timestampToleranceSeconds,
        int durationToleranceSeconds,
        int minimumRosterOverlap
) {
    public MPlusCorrelationProperties {
        requireRange(timestampToleranceSeconds, 1, 3_600, "timestampToleranceSeconds");
        requireRange(durationToleranceSeconds, 1, 600, "durationToleranceSeconds");
        requireRange(minimumRosterOverlap, 1, 5, "minimumRosterOverlap");
    }

    private static void requireRange(int value, int minimum, int maximum, String property) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(property + " must be between " + minimum + " and " + maximum);
        }
    }
}
