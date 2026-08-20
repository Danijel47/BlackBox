package com.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("wow.mplus-dungeons")
public record MPlusDungeonProperties(
        int expansionId,
        int targetLevel
) {
    public MPlusDungeonProperties {
        if (expansionId <= 0) {
            throw new IllegalArgumentException("expansionId must be positive");
        }
        if (targetLevel <= 0) {
            throw new IllegalArgumentException("targetLevel must be positive");
        }
    }
}
