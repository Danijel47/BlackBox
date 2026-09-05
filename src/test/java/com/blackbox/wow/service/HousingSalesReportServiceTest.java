package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardDecorService;
import com.blackbox.wow.client.SaddlebagExchangeClient;
import com.blackbox.wow.client.SaddlebagExchangeClient.RegionItem;
import com.blackbox.wow.properties.HousingMarketProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static com.blackbox.wow.service.HousingSalesReportService.HousingRanking.AVERAGE_PRICE;
import static com.blackbox.wow.service.HousingSalesReportService.HousingRanking.MARKET_PRICE;
import static com.blackbox.wow.service.HousingSalesReportService.HousingRanking.SALES;

class HousingSalesReportServiceTest {

    private static final Instant UPDATED_AT = Instant.parse("2026-09-03T01:35:57Z");

    @Test
    void ranksOnlyHousingItemsByRegionalDailySales() {
        BlizzardDecorService decorService = mock(BlizzardDecorService.class);
        SaddlebagExchangeClient marketClient = mock(SaddlebagExchangeClient.class);
        when(decorService.getDecorAuctionItemIds()).thenReturn(Set.of(10L, 20L, 30L));
        when(marketClient.getEuRetailItems(Set.of(10L, 20L, 30L))).thenReturn(List.of(
                item(10L, "Slow Sconce", 0.02D, 0.06D),
                item(20L, "Popular Chair", 0.15D, 1.75D),
                item(999L, "Popular Non-Decor", 0.90D, 500D),
                item(30L, "No Recorded Sales", 0D, 0D)
        ));
        HousingSalesReportService service = new HousingSalesReportService(
                decorService,
                marketClient,
                new HousingMarketProperties(2, 12, 24)
        );

        String report = service.rankedMessage(SALES);

        assertThat(report)
                .startsWith("Housing decor — top EU sellers")
                .contains("1. Popular Chair [20]")
                .contains("1.750/day | 15.0% sale")
                .contains("2. Slow Sconce [10]")
                .contains("Updated 2026-09-03 UTC")
                .contains("Saddlebag Exchange/TSM EU estimates")
                .doesNotContain("Popular Non-Decor", "No Recorded Sales");
        assertThat(report.indexOf("Popular Chair")).isLessThan(report.indexOf("Slow Sconce"));
    }

    @Test
    void reportsWhenNoHousingItemsHaveSalesData() {
        BlizzardDecorService decorService = mock(BlizzardDecorService.class);
        SaddlebagExchangeClient marketClient = mock(SaddlebagExchangeClient.class);
        when(decorService.getDecorAuctionItemIds()).thenReturn(Set.of(10L));
        when(marketClient.getEuRetailItems(Set.of(10L))).thenReturn(List.of(
                item(20L, "Other Item", 0.2D, 2D)
        ));
        HousingSalesReportService service = new HousingSalesReportService(
                decorService,
                marketClient,
                new HousingMarketProperties(10, 12, 24)
        );

        assertThat(service.rankedMessage(SALES))
                .isEqualTo("No auctionable housing decor with EU sales data is currently available.");
    }

    @Test
    void identifiesSaddlebagHttpFailuresWithoutExposingTheResponse() {
        BlizzardDecorService decorService = mock(BlizzardDecorService.class);
        SaddlebagExchangeClient marketClient = mock(SaddlebagExchangeClient.class);
        when(decorService.getDecorAuctionItemIds()).thenReturn(Set.of(10L));
        when(marketClient.getEuRetailItems(Set.of(10L))).thenThrow(
                HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null)
        );
        HousingSalesReportService service = new HousingSalesReportService(
                decorService,
                marketClient,
                new HousingMarketProperties(10, 12, 24)
        );

        assertThatThrownBy(() -> service.rankedMessage(SALES))
                .isInstanceOfSatisfying(HousingMarketUnavailableException.class, exception ->
                        assertThat(exception.adminMessage())
                                .isEqualTo("Housing sales data is temporarily unavailable: "
                                        + "Saddlebag Exchange TSM data returned HTTP 403."));
    }

    @Test
    void ranksHousingByAverageOrMarketPrice() {
        BlizzardDecorService decorService = mock(BlizzardDecorService.class);
        SaddlebagExchangeClient marketClient = mock(SaddlebagExchangeClient.class);
        Set<Long> decorItemIds = Set.of(10L, 20L);
        when(decorService.getDecorAuctionItemIds()).thenReturn(decorItemIds);
        when(marketClient.getEuRetailItems(decorItemIds)).thenReturn(List.of(
                item(10L, "High Average", 200_000_000L, 50_000_000L),
                item(20L, "High Market", 80_000_000L, 300_000_000L)
        ));
        HousingSalesReportService service = new HousingSalesReportService(
                decorService,
                marketClient,
                new HousingMarketProperties(2, 12, 24)
        );

        String averageReport = service.rankedMessage(AVERAGE_PRICE);
        String marketReport = service.rankedMessage(MARKET_PRICE);

        assertThat(averageReport)
                .startsWith("Housing decor — highest EU average prices")
                .containsSubsequence("1. High Average", "2. High Market");
        assertThat(marketReport)
                .startsWith("Housing decor — highest EU market prices")
                .containsSubsequence("1. High Market", "2. High Average");
    }

    private static RegionItem item(long itemId, String name, double saleRate, double soldPerDay) {
        return item(itemId, name, 80_000_000L, 100_000_000L, saleRate, soldPerDay);
    }

    private static RegionItem item(
            long itemId,
            String name,
            long averageSalePriceCopper,
            long marketValueCopper
    ) {
        return item(itemId, name, averageSalePriceCopper, marketValueCopper, 0.1D, 1D);
    }

    private static RegionItem item(
            long itemId,
            String name,
            long averageSalePriceCopper,
            long marketValueCopper,
            double saleRate,
            double soldPerDay
    ) {
        return new RegionItem(
                itemId,
                name,
                marketValueCopper,
                90_000_000L,
                averageSalePriceCopper,
                saleRate,
                soldPerDay,
                UPDATED_AT
        );
    }
}
