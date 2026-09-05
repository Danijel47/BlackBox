package com.blackbox.wow.client;

import com.blackbox.wow.properties.HousingMarketProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TsmPublicDataClientTest {

    private static final String REGION_ITEMS_URL =
            "https://public-data.tradeskillmaster.com/retail/eu/region/items.csv";

    @Test
    void identifiesTheApplicationWhenRequestingPublicData() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://public-data.tradeskillmaster.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(REGION_ITEMS_URL))
                .andExpect(header(HttpHeaders.USER_AGENT,
                        "BlackBox-WoW-Market (+https://github.com/Danijel47/TelegramBot)"))
                .andExpect(header(HttpHeaders.ACCEPT, "text/csv"))
                .andRespond(withSuccess(csv("""
                        itemId,name,marketValue,historical,avgSalePrice,saleRate,soldPerDay,updatedAt
                        264710,Dalaran Sun Sconce,142516963,145906850,66660900,0.015,0.06,2026-09-03T01:35:57Z
                        """), MediaType.parseMediaType("text/csv")));
        TsmPublicDataClient client = new TsmPublicDataClient(
                builder.build(),
                new HousingMarketProperties(10, 12, 24)
        );

        assertThat(client.getEuRetailRegionItems()).hasSize(1);
        server.verify();
    }

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
