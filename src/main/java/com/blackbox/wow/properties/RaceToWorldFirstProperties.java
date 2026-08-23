package com.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wow.rwf")
public record RaceToWorldFirstProperties(
        boolean enabled,
        long chatId,
        String raidSlug,
        String raidName,
        int bossCount,
        int expansionId
) {
}
