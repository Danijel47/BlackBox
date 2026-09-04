package com.blackbox.wow.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TsmPublicDataClientTest {

    @Test
    void parsesRegionalSalesDataAndQuotedItemNames() {
        byte[] response = csv("""
                itemId,name,marketValue,historical,avgSalePrice,saleRate,soldPerDay,updatedAt
                264710,Dalaran Sun Sconce,142516963,145906850,66660900,0.015,0.06,2026-09-03T01:35:57Z
                264711,"Books, Large Stack",50000000,49000000,42000000,0.125,1.75,2026-09-03T01:35:57Z
                """);

        var items = TsmPublicDataClient.parseRegionItems(response);

        assertThat(items).containsExactly(
                new TsmPublicDataClient.RegionItem(
                        264710L,
                        "Dalaran Sun Sconce",
                        142_516_963L,
                        145_906_850L,
                        66_660_900L,
                        0.015D,
                        0.06D,
                        Instant.parse("2026-09-03T01:35:57Z")
                ),
                new TsmPublicDataClient.RegionItem(
                        264711L,
                        "Books, Large Stack",
                        50_000_000L,
                        49_000_000L,
                        42_000_000L,
                        0.125D,
                        1.75D,
                        Instant.parse("2026-09-03T01:35:57Z")
                )
        );
    }

    @Test
    void rejectsOutOfRangeSaleMetrics() {
        byte[] response = csv("""
                itemId,name,marketValue,historical,avgSalePrice,saleRate,soldPerDay,updatedAt
                264710,Dalaran Sun Sconce,142516963,145906850,66660900,1.1,0.06,2026-09-03T01:35:57Z
                """);

        assertThatThrownBy(() -> TsmPublicDataClient.parseRegionItems(response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid CSV data");
    }

    @Test
    void rejectsDuplicateItemIds() {
        byte[] response = csv("""
                itemId,name,marketValue,historical,avgSalePrice,saleRate,soldPerDay,updatedAt
                264710,Dalaran Sun Sconce,142516963,145906850,66660900,0.015,0.06,2026-09-03T01:35:57Z
                264710,Dalaran Sun Sconce,142516963,145906850,66660900,0.015,0.06,2026-09-03T01:35:57Z
                """);

        assertThatThrownBy(() -> TsmPublicDataClient.parseRegionItems(response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate item ID");
    }

    private static byte[] csv(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
