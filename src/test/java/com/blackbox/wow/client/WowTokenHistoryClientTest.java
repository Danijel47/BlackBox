package com.blackbox.wow.client;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WowTokenHistoryClientTest {

    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");

    @Test
    void parsesValidatedChronologicalGoldPricesAsCopper() throws Exception {
        var response = JsonMapper.builder().build().readTree("""
                [
                  ["2026-07-21T09:29:30+00:00", 362603],
                  ["2026-07-21T09:49:30+00:00", 362841]
                ]
                """);

        var prices = WowTokenHistoryClient.parseHistory(response, NOW);

        assertThat(prices).containsExactly(
                new WowTokenHistoryClient.HistoricalTokenPrice(
                        Instant.parse("2026-07-21T09:29:30Z"),
                        3_626_030_000L
                ),
                new WowTokenHistoryClient.HistoricalTokenPrice(
                        Instant.parse("2026-07-21T09:49:30Z"),
                        3_628_410_000L
                )
        );
    }

    @Test
    void rejectsDuplicateOrUnorderedTimestamps() throws Exception {
        var response = JsonMapper.builder().build().readTree("""
                [
                  ["2026-08-20T10:00:00+00:00", 362603],
                  ["2026-08-20T10:00:00+00:00", 362841]
                ]
                """);

        assertThatThrownBy(() -> WowTokenHistoryClient.parseHistory(response, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strictly increasing");
    }

    @Test
    void rejectsOutOfRangePrices() throws Exception {
        var response = JsonMapper.builder().build().readTree("""
                [["2026-08-20T10:00:00+00:00", -1]]
                """);

        assertThatThrownBy(() -> WowTokenHistoryClient.parseHistory(response, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out-of-range");
    }
}
