package com.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("wow.mplus-team")
public record MPlusTeamProperties(int minimumSharedRuns, int maximumRows) {
    public MPlusTeamProperties {
        if (minimumSharedRuns < 1 || minimumSharedRuns > 100) {
            throw new IllegalArgumentException("minimumSharedRuns must be between 1 and 100");
        }
        if (maximumRows < 1 || maximumRows > 20) {
            throw new IllegalArgumentException("maximumRows must be between 1 and 20");
        }
    }
}
