package com.blackbox.wow.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WarcraftLogPlayerRunEntityTest {

    @Test
    void marksNewlyCollectedMetricsWithTheCurrentVersion() {
        WarcraftLogPlayerRunEntity run = new WarcraftLogPlayerRunEntity(
                "midnight-season-2", 1, "Linqq", "report", 1,
                Instant.parse("2026-08-19T10:00:00Z"), 7, "Dungeon", 10,
                9, 1, new BigDecimal("77"), new BigDecimal("49"), new BigDecimal("124013.3")
        );
        run.recordAvoidableDamage(new BigDecimal("456789.5"));

        assertThat(run.getMetricsVersion())
                .isEqualTo(WarcraftLogPlayerRunEntity.CURRENT_METRICS_VERSION);
        assertThat(run.getInterrupts()).isEqualTo(9);
        assertThat(run.getDeaths()).isEqualTo(1);
        assertThat(run.getParsePercentage()).isEqualByComparingTo("77");
        assertThat(run.getKeyParsePercentage()).isEqualByComparingTo("49");
        assertThat(run.getDamagePerSecond()).isEqualByComparingTo("124013.3");
        assertThat(run.getAvoidableDamage()).isEqualByComparingTo("456789.5");
    }
}
