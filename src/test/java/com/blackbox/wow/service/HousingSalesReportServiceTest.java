package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardDecorService;
import com.blackbox.wow.client.TsmPublicDataClient;
import com.blackbox.wow.client.TsmPublicDataClient.RegionItem;
import com.blackbox.wow.properties.HousingMarketProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HousingSalesReportServiceTest {

    private static final Instant UPDATED_AT = Instant.parse("2026-09-03T01:35:57Z");

    @Test
    void ranksOnlyHousingItemsByRegionalDailySales() {
        BlizzardDecorService decorService = mock(BlizzardDecorService.class);
        TsmPublicDataClient tsmClient = mock(TsmPublicDataClient.class);
        when(decorService.getDecorAuctionItemIds()).thenReturn(Set.of(10L, 20L, 30L));
        when(tsmClient.getEuRetailRegionItems()).thenReturn(List.of(
                item(10L, "Slow Sconce", 0.02D, 0.06D),
                item(20L, "Popular Chair", 0.15D, 1.75D),
                item(999L, "Popular Non-Decor", 0.90D, 500D),
                item(30L, "No Recorded Sales", 0D, 0D)
        ));
        HousingSalesReportService service = new HousingSalesReportService(
                decorService,
                tsmClient,
                new HousingMarketProperties(2, 12, 24)
        );

        String report = service.topSellingMessage();

        assertThat(report)
                .startsWith("Housing decor — top EU sellers")
                .contains("1. Popular Chair [20]")
                .contains("1.750/day | 15.0% sale")
                .contains("2. Slow Sconce [10]")
                .contains("Updated 2026-09-03 UTC")
                .doesNotContain("Popular Non-Decor", "No Recorded Sales");
        assertThat(report.indexOf("Popular Chair")).isLessThan(report.indexOf("Slow Sconce"));
    }

    @Test
    void reportsWhenNoHousingItemsHaveSalesData() {
        BlizzardDecorService decorService = mock(BlizzardDecorService.class);
        TsmPublicDataClient tsmClient = mock(TsmPublicDataClient.class);
        when(decorService.getDecorAuctionItemIds()).thenReturn(Set.of(10L));
        when(tsmClient.getEuRetailRegionItems()).thenReturn(List.of(item(20L, "Other Item", 0.2D, 2D)));
        HousingSalesReportService service = new HousingSalesReportService(
                decorService,
                tsmClient,
                new HousingMarketProperties(10, 12, 24)
        );

        assertThat(service.topSellingMessage())
                .isEqualTo("No auctionable housing decor with EU sales data is currently available.");
    }

    private static RegionItem item(long itemId, String name, double saleRate, double soldPerDay) {
        return new RegionItem(
                itemId,
                name,
                100_000_000L,
                90_000_000L,
                80_000_000L,
                saleRate,
                soldPerDay,
                UPDATED_AT
        );
    }
}
