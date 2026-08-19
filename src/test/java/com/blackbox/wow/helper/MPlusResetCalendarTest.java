package com.blackbox.wow.helper;

import com.blackbox.wow.properties.MPlusProgressProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MPlusResetCalendarTest {

    private final MPlusResetCalendar calendar = new MPlusResetCalendar(new MPlusProgressProperties(
            ZoneId.of("UTC"),
            DayOfWeek.WEDNESDAY,
            LocalTime.of(4, 0),
            List.of(new BigDecimal("1000"))
    ));

    @Test
    void startsANewPeriodExactlyAtTheResetBoundary() {
        Instant reset = Instant.parse("2026-08-19T04:00:00Z");

        assertThat(calendar.periodStart(reset)).isEqualTo(reset);
        assertThat(calendar.previousPeriodStart(reset))
                .isEqualTo(Instant.parse("2026-08-12T04:00:00Z"));
    }

    @Test
    void keepsThePreviousPeriodImmediatelyBeforeReset() {
        assertThat(calendar.periodStart(Instant.parse("2026-08-19T03:59:59Z")))
                .isEqualTo(Instant.parse("2026-08-12T04:00:00Z"));
    }
}
