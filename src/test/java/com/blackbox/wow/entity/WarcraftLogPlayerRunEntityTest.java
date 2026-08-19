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
                9, 1, new BigDecimal("73")
        );

        assertThat(run.getMetricsVersion())
                .isEqualTo(WarcraftLogPlayerRunEntity.CURRENT_METRICS_VERSION);
        assertThat(run.getInterrupts()).isEqualTo(9);
        assertThat(run.getDeaths()).isEqualTo(1);
    }
}
