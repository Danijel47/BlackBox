package com.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

@ConfigurationProperties("telegram.gaming-week-countdown")
public record GamingWeekCountdownProperties(
        boolean enabled,
        long chatId,
        LocalDate targetDate,
        ZoneId zone
) {
    public GamingWeekCountdownProperties {
        Objects.requireNonNull(targetDate, "targetDate must be configured");
        Objects.requireNonNull(zone, "zone must be configured");
    }
}
