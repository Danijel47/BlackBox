package com.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;
import java.util.Objects;

@ConfigurationProperties("wow.mplus-weekly-report")
public record MPlusWeeklyReportProperties(
        boolean enabled,
        long chatId,
        ZoneId zone
) {
    public MPlusWeeklyReportProperties {
        Objects.requireNonNull(zone, "zone must be configured");
    }
}
