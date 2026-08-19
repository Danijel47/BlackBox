package com.blackbox.wow.helper;

import com.blackbox.wow.properties.MPlusProgressProperties;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

public final class MPlusResetCalendar {

    private final MPlusProgressProperties properties;

    public MPlusResetCalendar(MPlusProgressProperties properties) {
        this.properties = properties;
    }

    public Instant periodStart(Instant instant) {
        ZonedDateTime zonedNow = instant.atZone(properties.resetZone());
        LocalDate resetDate = zonedNow.toLocalDate().with(
                TemporalAdjusters.previousOrSame(properties.resetDay())
        );
        ZonedDateTime reset = resetDate.atTime(properties.resetTime()).atZone(properties.resetZone());
        if (reset.isAfter(zonedNow)) {
            reset = reset.minusWeeks(1);
        }
        return reset.toInstant();
    }

    public Instant previousPeriodStart(Instant periodStart) {
        return periodStart(periodStart.minusSeconds(1));
    }
}
