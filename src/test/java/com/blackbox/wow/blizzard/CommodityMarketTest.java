package com.blackbox.wow.blizzard;

import com.blackbox.wow.blizzard.BlizzardApiClient.ApiSnapshot;
import com.blackbox.wow.blizzard.CommodityMarket.Offer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommodityMarketTest {

    @Test
    void pricesTheEntireQuantityFromCheapestToMostExpensiveAndKeepsQualityRanksSeparate() throws Exception {
        var data = JsonMapper.builder().build().readTree("""
                {"auctions": [
                  {"item":{"id":237359}, "quantity":900, "unit_price":200000},
                  {"item":{"id":237359}, "quantity":100, "unit_price":100000},
                  {"item":{"id":237361}, "quantity":1000, "unit_price":1},
                  {"item":{"id":237359}, "quantity":0, "unit_price":1},
                  {"item":{"id":237359}, "quantity":1000, "unit_price":"1"}
                ]}
                """);
        Instant updated = Instant.parse("2026-09-06T10:00:00Z");
        var market = CommodityMarket.fromSnapshot(new ApiSnapshot(data, updated, updated), Set.of(237359L));

        assertThat(market.purchaseCost(237359L, 1000)).contains(new BigDecimal("190000000"));
        assertThat(market.lowestUnitPrice(237359L)).contains(new BigDecimal("100000"));
        assertThat(market.purchaseCost(237359L, 1001)).isEmpty();
        assertThat(market.lowestUnitPrice(237361L)).isEmpty();
        assertThat(market.sourceUpdatedAt()).isEqualTo(updated);
    }

    @Test
    void handlesCostsLargerThanALongWithoutWrappingIntoAProfit() {
        var market = new CommodityMarket(Map.of(1L, List.of(new Offer(Long.MAX_VALUE, 2))), null, null);

        assertThat(market.purchaseCost(1L, 2))
                .contains(BigDecimal.valueOf(Long.MAX_VALUE).multiply(BigDecimal.TWO));
    }

    @Test
    void rejectsMalformedSnapshots() throws Exception {
        var data = JsonMapper.builder().build().readTree("{}");
        var snapshot = new ApiSnapshot(data, null, null);

        assertThatThrownBy(() -> CommodityMarket.fromSnapshot(snapshot, Set.of(1L)))
                .isInstanceOf(IllegalStateException.class);
    }
}
