package com.blackbox.wow.blizzard;

import com.blackbox.wow.blizzard.BlizzardApiClient.ApiSnapshot;
import com.blackbox.wow.properties.BlizzardApiProperties;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlizzardAuctionServiceTest {

    @Test
    void typicalMaterialPriceWeightsUnitsAndResistsSmallLowAndHighListings() throws Exception {
        var data = JsonMapper.builder().build().readTree("""
                {"auctions":[
                  {"item":{"id":1},"quantity":1,"unit_price":150000},
                  {"item":{"id":1},"quantity":100,"unit_price":300000},
                  {"item":{"id":1},"quantity":2,"unit_price":9000000},
                  {"item":{"id":2},"quantity":1000,"unit_price":10000},
                  {"item":{"id":1},"quantity":0,"unit_price":1},
                  {"item":{"id":1},"quantity":-10,"unit_price":1}
                ]}
                """);

        var prices = BlizzardAuctionService.materialPrices(data, 1);

        assertThat(prices.lowest().avgCopper()).isEqualTo(150_000);
        assertThat(prices.typical().avgCopper()).isEqualTo(300_000);
    }

    @Test
    void typicalMaterialPriceAveragesMiddleUnitsAtAnEvenSplit() throws Exception {
        var data = JsonMapper.builder().build().readTree("""
                {"auctions":[
                  {"item":{"id":1},"quantity":2,"buyout":400000},
                  {"item":{"id":1},"quantity":2,"unit_price":400000}
                ]}
                """);

        assertThat(BlizzardAuctionService.materialPrices(data, 1).typical().avgCopper())
                .isEqualTo(300_000);
        assertThat(BlizzardAuctionService.materialPrices(data, 2).typical().available()).isFalse();
        assertThat(BlizzardAuctionService.materialPrices(data, 2).lowest().available()).isFalse();
    }

    @Test
    void sharesOneTimestampedSnapshotBetweenExistingPricesAndProspecting() throws Exception {
        BlizzardApiClient api = mock(BlizzardApiClient.class);
        var cache = new BlizzardApiProperties.Cache(300, 300, 3600, 500_000, 1000, 250);
        var properties = new BlizzardApiProperties(null, null, null, null, "dynamic-eu", "en_GB", cache);
        var service = new BlizzardAuctionService(api, properties);
        var query = Map.of("namespace", "dynamic-eu");
        var data = JsonMapper.builder().build().readTree("""
                {"auctions":[{"item":{"id":237359},"quantity":1000,"unit_price":100000}]}
                """);
        Instant updated = Instant.parse("2026-09-06T10:00:00Z");
        String path = "/data/wow/auctions/commodities";
        when(api.defaultQuery()).thenReturn(query);
        when(api.getSnapshot(path, null, query)).thenReturn(new ApiSnapshot(data, updated, updated));

        assertThat(service.getRegionBuyPrice(237359L).gold()).isEqualTo(10);
        assertThat(service.getRegionAverage(237359L).gold()).isEqualTo(10);
        assertThat(service.getRegionMaterialPrices(237359L).typical().gold()).isEqualTo(10);
        assertThat(service.getCommodityMarket(Set.of(237359L)).sourceUpdatedAt()).isEqualTo(updated);
        verify(api, times(1)).getSnapshot(path, null, query);
    }

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
