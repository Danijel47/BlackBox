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

    @Test
    void usesTheLowestAvailableCommodityPriceForBuying() throws Exception {
        var response = JsonMapper.builder().build().readTree("""
                {
                  "auctions": [
                    {"item": {"id": 237365}, "quantity": 50, "unit_price": 10148400},
                    {"item": {"id": 237365}, "quantity": 2, "unit_price": 250000},
                    {"item": {"id": 237364}, "quantity": 10, "unit_price": 204500}
                  ]
                }
                """);

        var price = BlizzardAuctionService.lowestUnitPrice(response, 237365L);

        assertThat(price.available()).isTrue();
        assertThat(price.avgCopper()).isEqualTo(250_000L);
        assertThat(price.gold()).isEqualTo(25L);
    }
}
