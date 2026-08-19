package com.blackbox.wow.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

@ConfigurationProperties("wow.mplus-progress")
public record MPlusProgressProperties(
        ZoneId resetZone,
        DayOfWeek resetDay,
        LocalTime resetTime,
        List<BigDecimal> milestones
) {
    public MPlusProgressProperties {
        Objects.requireNonNull(resetZone, "resetZone must be configured");
        Objects.requireNonNull(resetDay, "resetDay must be configured");
        Objects.requireNonNull(resetTime, "resetTime must be configured");
        milestones = milestones == null ? List.of() : milestones.stream()
                .filter(score -> score != null && score.signum() > 0)
                .map(BigDecimal::stripTrailingZeros)
                .distinct()
                .sorted()
                .toList();
    }
}
