package com.blackbox.wow.blizzard;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BlizzardAuctionServiceTest {

    @Test
    void parsesTheTokenPriceAndBlizzardUpdateTimestamp() throws Exception {
        var response = JsonMapper.builder().build().readTree("""
                {
                  "last_updated_timestamp": 1787299200000,
                  "price": 3456789000
                }
                """);

        var snapshot = BlizzardAuctionService.parseWowTokenPriceSnapshot(response);

        assertThat(snapshot.price().available()).isTrue();
        assertThat(snapshot.price().avgCopper()).isEqualTo(3_456_789_000L);
        assertThat(snapshot.sourceUpdatedAt()).isEqualTo(Instant.parse("2026-08-21T08:00:00Z"));
    }

    @Test
    void treatsAResponseWithoutAPositivePriceAsUnavailable() throws Exception {
        var response = JsonMapper.builder().build().readTree("{} ");

        var snapshot = BlizzardAuctionService.parseWowTokenPriceSnapshot(response);

        assertThat(snapshot.price().available()).isFalse();
        assertThat(snapshot.sourceUpdatedAt()).isNull();
    }
}
